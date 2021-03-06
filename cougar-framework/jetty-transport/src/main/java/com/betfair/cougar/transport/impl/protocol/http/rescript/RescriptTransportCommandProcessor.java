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

package com.betfair.cougar.transport.impl.protocol.http.rescript;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;

import com.betfair.cougar.api.ExecutionContextWithTokens;
import com.betfair.cougar.api.ResponseCode;
import com.betfair.cougar.api.security.IdentityToken;
import com.betfair.cougar.api.security.InferredCountryResolver;
import com.betfair.cougar.core.api.OperationBindingDescriptor;
import com.betfair.cougar.core.api.ServiceBindingDescriptor;
import com.betfair.cougar.core.api.ev.ExecutionResult;
import com.betfair.cougar.core.api.ev.OperationDefinition;
import com.betfair.cougar.core.api.ev.OperationKey;
import com.betfair.cougar.core.api.ev.TimeConstraints;
import com.betfair.cougar.core.api.exception.CougarException;
import com.betfair.cougar.core.api.exception.CougarFrameworkException;
import com.betfair.cougar.core.api.exception.CougarValidationException;
import com.betfair.cougar.core.api.exception.PanicInTheCougar;
import com.betfair.cougar.core.api.exception.ServerFaultCode;
import com.betfair.cougar.core.api.transcription.EnumUtils;
import com.betfair.cougar.core.impl.DefaultTimeConstraints;
import com.betfair.cougar.logging.CougarLogger;
import com.betfair.cougar.logging.CougarLoggingUtils;
import com.betfair.cougar.marshalling.api.databinding.DataBindingFactory;
import com.betfair.cougar.marshalling.api.databinding.FaultMarshaller;
import com.betfair.cougar.marshalling.api.databinding.Marshaller;
import com.betfair.cougar.marshalling.impl.databinding.DataBindingManager;
import com.betfair.cougar.transport.api.CommandResolver;
import com.betfair.cougar.transport.api.ExecutionCommand;
import com.betfair.cougar.transport.api.RequestTimeResolver;
import com.betfair.cougar.transport.api.TransportCommand;
import com.betfair.cougar.transport.api.protocol.http.GeoLocationDeserializer;
import com.betfair.cougar.transport.api.protocol.http.HttpCommand;
import com.betfair.cougar.transport.api.protocol.http.HttpServiceBindingDescriptor;
import com.betfair.cougar.transport.api.protocol.http.ResponseCodeMapper;
import com.betfair.cougar.transport.api.protocol.http.rescript.RescriptIdentityTokenResolver;
import com.betfair.cougar.transport.api.protocol.http.rescript.RescriptOperationBindingDescriptor;
import com.betfair.cougar.transport.api.protocol.http.rescript.RescriptResponse;
import com.betfair.cougar.transport.impl.protocol.http.AbstractTerminateableHttpCommandProcessor;
import com.betfair.cougar.util.geolocation.GeoIPLocator;
import com.betfair.cougar.util.stream.ByteCountingInputStream;
import com.betfair.cougar.util.stream.ByteCountingOutputStream;
import org.springframework.jmx.export.annotation.ManagedResource;

/**
 * TransportCommandProcessor for the Rescript protocol.
 * Responsible for resolving the operation and arguments from the command, 
 * and for writing the result or exception from the operation to the response.
 */
@ManagedResource
public class RescriptTransportCommandProcessor extends AbstractTerminateableHttpCommandProcessor {
    final static CougarLogger logger = CougarLoggingUtils.getLogger(RescriptTransportCommandProcessor.class);
	private Map<String, RescriptOperationBinding> bindings = new HashMap<String, RescriptOperationBinding>();

    public RescriptTransportCommandProcessor(GeoIPLocator geoIPLocator, GeoLocationDeserializer deserializer, String uuidHeader,
                                             String requestTimeoutHeader, RequestTimeResolver requestTimeResolver) {
        this(geoIPLocator, deserializer, uuidHeader, requestTimeoutHeader, requestTimeResolver, null);
    }

	public RescriptTransportCommandProcessor(GeoIPLocator geoIPLocator, GeoLocationDeserializer deserializer, String uuidHeader,
                                             String requestTimeoutHeader, RequestTimeResolver requestTimeResolver, InferredCountryResolver<HttpServletRequest> countryResolver) {
		super(geoIPLocator, deserializer, uuidHeader, countryResolver, requestTimeoutHeader, requestTimeResolver);
		setName("RescriptTransportCommandProcessor");
	}

    @Override
	public void onCougarStart() {
		for (ServiceBindingDescriptor bindingDescriptor : getServiceBindingDescriptors()) {
			for (OperationBindingDescriptor opDesc : bindingDescriptor.getOperationBindings()) {
				bindOperation(bindingDescriptor, opDesc);
			}
		}
	}
	
	public void bindOperation(ServiceBindingDescriptor serviceBindingDescriptor, OperationBindingDescriptor bindingDescriptor) {
		OperationDefinition operationDefinition = getOperationDefinition(bindingDescriptor.getOperationKey());
		if (operationDefinition!=null) {
			RescriptOperationBindingDescriptor rescriptOperationBindingDescriptor = (RescriptOperationBindingDescriptor) bindingDescriptor;

            HttpServiceBindingDescriptor httpServiceBindingDescriptor = (HttpServiceBindingDescriptor) serviceBindingDescriptor;
            String uri = stripMinorVersionFromUri(httpServiceBindingDescriptor.getServiceContextPath() + httpServiceBindingDescriptor.getServiceVersion());
            if (uri == null) uri = ""; // defensive
            uri += rescriptOperationBindingDescriptor.getURI();
            if (bindings.containsKey(uri)) {
                throw new PanicInTheCougar("More than one operation is bound to the path [" + uri + "] edit your operation paths so that this is unique, existing = "+bindings.get(uri)+", new = "+bindingDescriptor);
            }
			bindings.put(uri, new RescriptOperationBinding(rescriptOperationBindingDescriptor, operationDefinition, hardFailEnumDeserialisation));
		}
	}	
	


	@Override
	protected CommandResolver<HttpCommand> createCommandResolver(final HttpCommand command) {
        String uri = stripMinorVersionFromUri(command.getOperationPath());

		final RescriptOperationBinding binding = bindings.get(uri);

		return new SingleExecutionCommandResolver<HttpCommand>() {
			
			private ExecutionContextWithTokens context;
			private ExecutionCommand executionCommand;

			@Override
			public ExecutionContextWithTokens resolveExecutionContext() {

				if (context == null) {
                    context = RescriptTransportCommandProcessor.this.resolveExecutionContext(command, command.getRequest(), command.getClientX509CertificateChain());
				}
				return context;
			}

			@Override
			public ExecutionCommand resolveExecutionCommand() {
				if (binding!=null) {
					if (executionCommand == null) {
						executionCommand = RescriptTransportCommandProcessor.this.resolveExecutionCommand(
								binding, 
								command, 
								resolveExecutionContext());
					}
					return executionCommand;
				}
				throw new CougarValidationException(ServerFaultCode.NoSuchOperation,
						"The request could not be resolved to an operation");
			}
		};

	}
	
	private ExecutionCommand resolveExecutionCommand(final RescriptOperationBinding binding, final HttpCommand command, final ExecutionContextWithTokens context) {
		final MediaType requestMediaType = getContentTypeNormaliser().getNormalisedRequestMediaType(command.getRequest());
		final String encoding = getContentTypeNormaliser().getNormalisedEncoding(command.getRequest());
		ByteCountingInputStream iStream = null;
		Object[] args = null;
		try {
			iStream = createByteCountingInputStream(command.getRequest().getInputStream());
            EnumUtils.setHardFailureForThisThread(hardFailEnumDeserialisation);
			args = binding.resolveArgs(command.getRequest(), iStream, requestMediaType, encoding);
		} catch (IOException ioe) {
			throw new CougarFrameworkException("Unable to resolve arguments for operation " + binding.getOperationKey(), ioe);
		} finally {
			try {
                            if (iStream != null) {
                                iStream.close();
                            }
			} catch (IOException ignored) {
				ignored.printStackTrace();
			}
		}
		final Object[] finalArgs = args;
        final TimeConstraints realTimeConstraints = DefaultTimeConstraints.rebaseFromNewStartTime(context.getRequestTime(), readRawTimeConstraints(command.getRequest()));
		final long bytesRead = iStream != null ? iStream.getCount() : 0;
		return new ExecutionCommand() {
            public Object[] getArgs() {
                return finalArgs;
            }

            public OperationKey getOperationKey() {
                return binding.getOperationKey();
            }

            @Override
            public TimeConstraints getTimeConstraints() {
                return realTimeConstraints;
            }

            public void onResult(ExecutionResult executionResult) {
                    if (executionResult.getResultType() == ExecutionResult.ResultType.Success) {
                        writeResponse(command, binding, executionResult.getResult(), context, requestMediaType, bytesRead);
                    } else if (executionResult.getResultType() == ExecutionResult.ResultType.Fault) {
                        writeErrorResponse(command, executionResult.getFault(), context, requestMediaType, bytesRead);
				    }
                }
        };
	}

	@Override
	protected void writeErrorResponse(HttpCommand command, ExecutionContextWithTokens context, CougarException error) {
		writeErrorResponse(command, error, context, null, 0);
	}
	
	protected final void writeErrorResponse(HttpCommand command, CougarException error,
			ExecutionContextWithTokens context, MediaType requestMediaType, long bytesRead) {
        incrementErrorsWritten();
		final HttpServletRequest request = command.getRequest();
		final HttpServletResponse response = command.getResponse();
		if (command.getStatus() == TransportCommand.CommandStatus.InProcess) {
			try {
                MediaType responseMediaType = null;
                long bytesWritten = 0;
                if(error.getResponseCode() != ResponseCode.CantWriteToSocket) {

                    ResponseCodeMapper.setResponseStatus(response, error.getResponseCode());
                    try {
                        responseMediaType = getContentTypeNormaliser().getNormalisedResponseMediaType(request);
                    } catch (CougarValidationException e) {
                        responseMediaType = MediaType.APPLICATION_XML_TYPE;
                    }
                    response.setContentType(responseMediaType.toString());
                    DataBindingFactory dataBindingFactory = DataBindingManager.getInstance().getFactory(responseMediaType);
                    FaultMarshaller marshaller = dataBindingFactory.getFaultMarshaller();
                    ByteCountingOutputStream out = null;
                    try {
                        out = new ByteCountingOutputStream(response.getOutputStream());
                        marshaller.marshallFault(out, error.getFault(), getContentTypeNormaliser().getNormalisedEncoding(request));
                        bytesWritten = out.getCount();
                    } catch (IOException e) {
                        handleResponseWritingIOException(e, error.getClass());
                    } finally {
                        closeStream(out);
                    }
                } else {
                    logger.log(Level.FINE, "Skipping error handling for a request where the output channel/socket has been prematurely closed");
                }
                logAccess(command,
                        resolveContextForErrorHandling(context, command), bytesRead,
                        bytesWritten, requestMediaType,
                        responseMediaType, error.getResponseCode());

			} finally {
				command.onComplete();
			}
		}
	}


	protected int writeResponse(HttpCommand command, RescriptOperationBinding binding,
			Object result, ExecutionContextWithTokens context, MediaType requestMediaType, long bytesRead) {
		final HttpServletRequest request = command.getRequest();
		final HttpServletResponse response = command.getResponse();
        final RescriptIdentityTokenResolver tokenResolver = (RescriptIdentityTokenResolver)command.getIdentityTokenResolver();
		if (command.getStatus() == TransportCommand.CommandStatus.InProcess) {			
			try {
				if (result instanceof ResponseCode) {
					ResponseCodeMapper.setResponseStatus(response, ((ResponseCode)result));
	                logAccess(command,
							context, bytesRead,
							0, requestMediaType,
							null, (ResponseCode)result);
				} else {
                    if (context != null && context.getIdentity() != null && tokenResolver != null) {
                        writeIdentity(context.getIdentityTokens(), new IdentityTokenIOAdapter() {

                            @Override
                            public void rewriteIdentityTokens(List<IdentityToken> identityTokens) {
                                tokenResolver.rewrite(identityTokens, response);
                            }

                            @Override
                            public boolean isRewriteSupported() {
                                return tokenResolver.isRewriteSupported();
                            }
                        });
                    }

                    //If the operation returns void, then return 200
                    if (binding.getBindingDescriptor().voidReturnType()) {
                        ResponseCodeMapper.setResponseStatus(response, ResponseCode.Ok);
                        logAccess(command,
                                context, bytesRead,
                                0, requestMediaType,
                                null, ResponseCode.Ok);
                    } else {
                        RescriptResponse responseWrapper = binding.getBindingDescriptor().getResponseClass().newInstance();
                        responseWrapper.setResult(result);
                        MediaType responseMediaType = getContentTypeNormaliser().getNormalisedResponseMediaType(request);
                        DataBindingFactory dataBindingFactory = DataBindingManager.getInstance().getFactory(responseMediaType);
                        Marshaller marshaller = dataBindingFactory.getMarshaller();
                        String encoding = getContentTypeNormaliser().getNormalisedEncoding(request);
                        response.setContentType(responseMediaType.toString());
                        ByteCountingOutputStream out = null;
                        try {
                            out = new ByteCountingOutputStream(response.getOutputStream());
                            Object toMarshall = responseWrapper;
                            if (responseMediaType.getSubtype().equals("json")) {
                                toMarshall = responseWrapper.getResult();
                            }
                            marshaller.marshall(out, toMarshall, encoding);
                            logAccess(command,
                                    context, bytesRead,
                                    out.getCount(), requestMediaType,
                                    responseMediaType, ResponseCode.Ok);
                        } finally {
                            closeStream(out);
                        }
                    }
				}
			} catch (Exception e) {
                writeErrorResponse(command, context, handleResponseWritingIOException(e, result.getClass()));
            } finally {
				command.onComplete();
			}
		}
		return 0;
	}
}
