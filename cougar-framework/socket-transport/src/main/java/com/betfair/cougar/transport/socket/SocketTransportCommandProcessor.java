/*
 * Copyright 2013, The Sporting Exchange Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.betfair.cougar.transport.socket;

import com.betfair.cougar.api.ExecutionContextWithTokens;
import com.betfair.cougar.core.api.*;
import com.betfair.cougar.core.api.ev.ConnectedResponse;
import com.betfair.cougar.core.api.ev.ExecutionResult;
import com.betfair.cougar.core.api.ev.OperationDefinition;
import com.betfair.cougar.core.api.ev.OperationKey;
import com.betfair.cougar.core.api.ev.TimeConstraints;
import com.betfair.cougar.core.api.exception.*;
import com.betfair.cougar.core.api.security.IdentityResolverFactory;
import com.betfair.cougar.core.api.transcription.EnumDerialisationException;
import com.betfair.cougar.core.api.transcription.TranscriptionException;
import com.betfair.cougar.core.impl.DefaultTimeConstraints;
import com.betfair.cougar.logging.CougarLogger;
import com.betfair.cougar.logging.CougarLoggingUtils;
import com.betfair.cougar.logging.EventLoggingRegistry;
import com.betfair.cougar.marshalling.api.socket.RemotableMethodInvocationMarshaller;
import com.betfair.cougar.netutil.nio.CougarProtocol;
import com.betfair.cougar.netutil.nio.NioLogger;
import com.betfair.cougar.netutil.nio.NioUtils;
import com.betfair.cougar.netutil.nio.TerminateSubscription;
import com.betfair.cougar.transport.api.CommandResolver;
import com.betfair.cougar.transport.api.CommandValidator;
import com.betfair.cougar.transport.api.ExecutionCommand;
import com.betfair.cougar.transport.api.protocol.CougarObjectInput;
import com.betfair.cougar.transport.api.protocol.CougarObjectOutput;
import com.betfair.cougar.transport.impl.AbstractCommandProcessor;
import com.betfair.cougar.transport.impl.protocol.SSLCipherUtils;
import com.betfair.cougar.util.X509CertificateUtils;
import org.apache.mina.filter.SSLFilter;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.X509Certificate;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

@ManagedResource
public class SocketTransportCommandProcessor extends AbstractCommandProcessor<SocketTransportCommand> implements GateListener {
    private static CougarLogger logger = CougarLoggingUtils.getLogger(SocketTransportCommandProcessor.class);

    private RemotableMethodInvocationMarshaller marshaller;

    private EventLoggingRegistry registry;


    private IdentityResolverFactory identityResolverFactory;
    private ServerConnectedObjectManager connectedObjectManager;

    private Map<String, ServiceBindingDescriptor> serviceBindingDescriptors = new HashMap<String, ServiceBindingDescriptor>();
    private Map<OperationKey, OperationDefinition> bindings = new HashMap<OperationKey, OperationDefinition>();
    private Map<String, OperationKey> namedOperations = new HashMap<String, OperationKey>();
    private int priority = 1;
    private NioLogger nioLogger;
    private AtomicLong outstandingRequests = new AtomicLong();

    private int unknownCipherKeyLength;

    public void setStartingGate(CougarStartingGate startingGate) {
        startingGate.registerStartingListener(this);
    }

    @Override
    public String getName() {
        return "SocketTransportCommandProcessor";
    }

    @Override
    public void process(SocketTransportCommand command) {
        if (command instanceof SocketTransportRPCCommand) {
            incrementOutstandingRequests();
            super.process(command);    //To change body of overridden methods use File | Settings | File Templates.
        } else {
            try {
                CougarObjectInput input = command.getInput();
                Object eventPayload = input.readObject();
                input.close();

                if (eventPayload instanceof TerminateSubscription) {
                    connectedObjectManager.terminateSubscription(command.getSession(), (TerminateSubscription) eventPayload);
                } else {
                    logger.log(Level.SEVERE, "SocketTransportCommandProcessor - Received unexpected event type: " + eventPayload + " - closing session");
                    nioLogger.log(NioLogger.LoggingLevel.SESSION, command.getSession(), "SocketTransportCommandProcessor - Received unexpected event type: %s - closing session", eventPayload);
                    command.getSession().close();
                }
            } catch (Exception e) {
                if (e instanceof IOException) {
                    logger.log(Level.FINE, "IO exception from session " + NioUtils.getSessionId(command.getSession()), e);
                } else {
                    logger.log(Level.WARNING, "Unexpected exception from session " + NioUtils.getSessionId(command.getSession()), e);
                }
                nioLogger.log(NioLogger.LoggingLevel.SESSION, command.getSession(), "SocketTransportCommandProcessor - %s received: %s - closing session", e.getClass().getSimpleName(), e.getMessage());
                command.getSession().close();
            }
        }
    }

    @Override
    protected CommandResolver<SocketTransportCommand> createCommandResolver(SocketTransportCommand command) {
        try {

            final CougarObjectInput in = command.getInput();

            // rpc call
            if (command instanceof SocketTransportRPCCommand) {
                final SocketTransportRPCCommand rpcCommand = (SocketTransportRPCCommand) command;

                // we only want to do this once ideally
                X509Certificate[] clientCertChain = (X509Certificate[]) rpcCommand.getSession().getAttribute(CougarProtocol.CLIENT_CERTS_ATTR_NAME);
                Integer transportSecurityStrengthFactor = (Integer) rpcCommand.getSession().getAttribute(CougarProtocol.TSSF_ATTR_NAME);
                Object sslSession = rpcCommand.getSession().getAttribute(SSLFilter.SSL_SESSION);
                if (sslSession != null) {
                    if (clientCertChain == null) {
                        SSLSession session = (SSLSession) sslSession;
                        try {
                            clientCertChain = X509CertificateUtils.convert(session.getPeerCertificateChain());
                        }
                        catch (SSLPeerUnverifiedException spue) {
                            // since we don't know in here that the client cert was required, we'll just ignore this..
                            logger.log(Level.FINE, "SSL peer unverified");
                            clientCertChain = new X509Certificate[0];
                        }
                        rpcCommand.getSession().setAttribute(CougarProtocol.CLIENT_CERTS_ATTR_NAME, clientCertChain);
                    }
                    if (transportSecurityStrengthFactor == null) {
                        SSLSession session = (SSLSession) sslSession;

                        transportSecurityStrengthFactor = SSLCipherUtils.deduceKeyLength(session.getCipherSuite(), unknownCipherKeyLength);
                        rpcCommand.getSession().setAttribute(CougarProtocol.TSSF_ATTR_NAME, transportSecurityStrengthFactor);
                    }
                }
                else {
                    if (clientCertChain == null) {
                        clientCertChain = new X509Certificate[0];
                        rpcCommand.getSession().setAttribute(CougarProtocol.CLIENT_CERTS_ATTR_NAME, clientCertChain);
                    }
                    if (transportSecurityStrengthFactor == null) {
                        transportSecurityStrengthFactor = 0;
                        rpcCommand.getSession().setAttribute(CougarProtocol.TSSF_ATTR_NAME, transportSecurityStrengthFactor);
                    }
                }
                byte protocolVersion = CougarProtocol.getProtocolVersion(command.getSession());
                ExecutionContextWithTokens context = marshaller.readExecutionContext(in, command.getRemoteAddress(), clientCertChain, transportSecurityStrengthFactor, protocolVersion);
                final SocketRequestContextImpl requestContext = new SocketRequestContextImpl(context);
                OperationKey remoteOperationKey = marshaller.readOperationKey(in);
                OperationDefinition opDef = findCompatibleBinding(remoteOperationKey);
                if (opDef == null) {
                    throw new CougarFrameworkException("Can't find operation definition in bindings for operation named '" + remoteOperationKey.getOperationName() + "'");
                }
                final OperationKey operationKey = opDef.getOperationKey(); // safer to read it from locally
                final OperationDefinition operationDefinition = getExecutionVenue().getOperationDefinition(operationKey);
                final Object[] args = marshaller.readArgs(operationDefinition.getParameters(), in);
                TimeConstraints rawTimeConstraints = marshaller.readTimeConstraintsIfPresent(in, protocolVersion);
                final TimeConstraints timeConstraints = DefaultTimeConstraints.rebaseFromNewStartTime(context.getRequestTime(), rawTimeConstraints);
                final ExecutionCommand exec = new ExecutionCommand() {

                    @Override
                    public Object[] getArgs() {
                        return args;
                    }

                    @Override
                    public OperationKey getOperationKey() {
                        return operationKey;
                    }

                    @Override
                    public void onResult(ExecutionResult result) {
                        if (result.getResultType() == ExecutionResult.ResultType.Success) {
                            if (operationKey.getType() == OperationKey.Type.ConnectedObject) {
                                connectedObjectManager.addSubscription(SocketTransportCommandProcessor.this, rpcCommand, (ConnectedResponse) result.getResult(), operationDefinition, requestContext, requestContext.getConnectedObjectLogExtension());
                            } else {
                                writeSuccessResponse(rpcCommand, result);
                            }
                        } else if (result.getResultType() == ExecutionResult.ResultType.Fault) {
                            writeErrorResponse(rpcCommand, requestContext, result.getFault());
                        }
                    }

                    @Override
                    public TimeConstraints getTimeConstraints() {
                        return timeConstraints;
                    }
                };

                return new SingleExecutionCommandResolver<SocketTransportCommand>() {

                    @Override
                    public ExecutionCommand resolveExecutionCommand() {
                        return exec;
                    }

                    @Override
                    public ExecutionContextWithTokens resolveExecutionContext() {
                        return requestContext;
                    }
                };
            } else {
                logger.log(Level.SEVERE, "SocketTransportCommandProcessor - Received an event request for processing like an rpc request, closing session");
                nioLogger.log(NioLogger.LoggingLevel.SESSION, command.getSession(), "SocketTransportCommandProcessor - Received an event request for processing like an rpc request, closing session");
                command.getSession().close();
                throw new IllegalStateException("Received an event request for processing like an rpc request");
            }
        } catch (EnumDerialisationException ede) {
            final String message = ede.getMessage();
            logger.log(Level.FINER, message, ede);
            throw new CougarServiceException(ServerFaultCode.BinDeserialisationParseFailure, message, ede);
        } catch (CougarException ce) {
            throw ce;
        } catch (TranscriptionException e) {
            final String message = "transcription exception deserialising invocation";
            logger.log(Level.FINER, message, e);
            throw new CougarServiceException(ServerFaultCode.ClassConversionFailure, message, e);
        } catch (Exception e) {
            final String message = "Unable to deserialise invocation";
            logger.log(Level.FINER, message, e);
            throw new CougarServiceException(ServerFaultCode.BinDeserialisationParseFailure, message, e);
        }

    }

    private OperationDefinition findCompatibleBinding(OperationKey remoteOperationKey) {
        OperationDefinition ret = bindings.get(remoteOperationKey);
        if (ret != null) {
            return ret;
        }
        // if the client is requesting the same major version of this method, then let it through..
        OperationKey myKeyForThisMethod = namedOperations.get(remoteOperationKey.toString(false));
        // ie, we've never heard of this method
        if (myKeyForThisMethod == null) {
            return null;
        }
        ServiceVersion myVersion = myKeyForThisMethod.getVersion();
        ServiceVersion remoteVersion = remoteOperationKey.getVersion();
        if (myVersion.getMajor() == remoteVersion.getMajor()) {
            return bindings.get(myKeyForThisMethod);
        }
        return null;
    }

    protected boolean writeSuccessResponse(SocketTransportRPCCommand command, ExecutionResult result) {
        CougarObjectOutput out = command.getOutput();
        try {
            synchronized (out) {
                marshaller.writeInvocationResponse(new InvocationResponseImpl(result.getResult(), null), out, CougarProtocol.getProtocolVersion(command.getSession()));
                out.flush();
            }
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to stream response to client", e);
            return false;
        } finally {
            decrementOutstandingRequests();
        }

    }

    @Override
    protected void writeErrorResponse(SocketTransportCommand command, ExecutionContextWithTokens context, CougarException e) {
        if (command instanceof SocketTransportRPCCommand) {
            SocketTransportRPCCommand rpcCommand = (SocketTransportRPCCommand) command;
            incrementErrorsWritten();
            CougarObjectOutput out = rpcCommand.getOutput();
            try {
                synchronized (out) {
                    marshaller.writeInvocationResponse(new InvocationResponseImpl(null, e), out, CougarProtocol.getProtocolVersion(command.getSession()));
                    out.flush();
                }
            } catch (Exception ex) {
                //An unrecoverable exception, lest we end up in an infinite loop
                logger.log(Level.SEVERE, "Unable to stream error response to client", ex);
            } finally {
                decrementOutstandingRequests();
            }
        } else {
            logger.log(Level.SEVERE, "SocketTransportCommandProcessor - Trying to write an error response for an event, closing session");
            nioLogger.log(NioLogger.LoggingLevel.SESSION, command.getSession(), "SocketTransportCommandProcessor - Trying to write an error response for an event, closing session");
            command.getSession().close();
        }
    }

    @Override
    protected List<CommandValidator<SocketTransportCommand>> getCommandValidators() {
        return Collections.emptyList();
    }

    @Override
    public void onCougarStart() {
        for (ServiceBindingDescriptor bindingDescriptor : getServiceBindingDescriptors()) {
            for (OperationBindingDescriptor opDesc : bindingDescriptor.getOperationBindings()) {
                bindOperation(opDesc);
            }
        }
    }

    /**
     * Returns all the ServiceBindindDescriptors registered via the bind method.
     *
     * @return
     */
    private Iterable<ServiceBindingDescriptor> getServiceBindingDescriptors() {
        return serviceBindingDescriptors.values();
    }

    public void bindOperation(OperationBindingDescriptor bindingDescriptor) {
        OperationDefinition operationDefinition = getOperationDefinition(bindingDescriptor.getOperationKey());
        if (operationDefinition != null) {
            bindings.put(operationDefinition.getOperationKey(), operationDefinition);
            // for version difference negotiation
            namedOperations.put(operationDefinition.getOperationKey().toString(false), operationDefinition.getOperationKey());
        }
    }

    @Override
    public void bind(ServiceBindingDescriptor bindingDescriptor) {
        String servicePlusMajorVersion = bindingDescriptor.getServiceName() +
                "-v" + bindingDescriptor.getServiceVersion().getMajor();

        if (serviceBindingDescriptors.containsKey(servicePlusMajorVersion)) {
            throw new PanicInTheCougar("More than one version of service [" + bindingDescriptor.getServiceName() +
                    "] is attempting to be bound for the same major version. The clashing versions are [" +
                    serviceBindingDescriptors.get(servicePlusMajorVersion).getServiceVersion() + ", " +
                    bindingDescriptor.getServiceVersion() +
                    "] - only one instance of a service is permissable for each major version");
        }
        serviceBindingDescriptors.put(servicePlusMajorVersion, bindingDescriptor);
    }

    @Required
    public void setMarshaller(RemotableMethodInvocationMarshaller marshaller) {
        this.marshaller = marshaller;
    }

    public RemotableMethodInvocationMarshaller getMarshaller() {
        return marshaller;
    }

    public EventLoggingRegistry getRegistry() {
        return registry;
    }

    public IdentityResolverFactory getIdentityResolverFactory() {
        return identityResolverFactory;
    }

    public void setIdentityResolverFactory(IdentityResolverFactory identityResolverFactory) {
        this.identityResolverFactory = identityResolverFactory;
    }

    @Required
    public void setRegistry(EventLoggingRegistry registry) {
        this.registry = registry;
    }

    public void setConnectedObjectManager(ServerConnectedObjectManager connectedObjectManager) {
        this.connectedObjectManager = connectedObjectManager;
    }

    @Override
    @ManagedAttribute
    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public void setNioLogger(NioLogger nioLogger) {
        this.nioLogger = nioLogger;
    }

    private void incrementOutstandingRequests() {
        outstandingRequests.incrementAndGet();
    }

    private void decrementOutstandingRequests() {
        outstandingRequests.decrementAndGet();
    }

    @ManagedAttribute
    public long getOutstandingRequests() {
        return outstandingRequests.get();
    }

    public void setUnknownCipherKeyLength(int unknownCipherKeyLength) {
        this.unknownCipherKeyLength = unknownCipherKeyLength;
    }

    @ManagedAttribute
    public int getUnknownCipherKeyLength() {
        return unknownCipherKeyLength;
    }
}
