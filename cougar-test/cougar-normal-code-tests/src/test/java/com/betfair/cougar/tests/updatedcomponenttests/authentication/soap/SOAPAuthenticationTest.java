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

// Originally from UpdatedComponentTests/Authentication/SOAP/SOAP_Authentication.xls;
package com.betfair.cougar.tests.updatedcomponenttests.authentication.soap;

import com.betfair.testing.utils.cougar.misc.XMLHelpers;
import com.betfair.testing.utils.cougar.assertions.AssertionUtils;
import com.betfair.testing.utils.cougar.beans.HttpCallBean;
import com.betfair.testing.utils.cougar.beans.HttpResponseBean;
import com.betfair.testing.utils.cougar.manager.AccessLogRequirement;
import com.betfair.testing.utils.cougar.manager.CougarManager;
import com.betfair.testing.utils.cougar.manager.RequestLogRequirement;

import org.testng.annotations.Test;
import org.w3c.dom.Document;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * Ensure when the auth header credentials are provided, the credentials are rewritten in the response headers and also returned in the response body.
 */
public class SOAPAuthenticationTest {
    @Test
    public void doTest() throws Exception {
        
        XMLHelpers xMLHelpers1 = new XMLHelpers();
        Document createAsDocument1 = xMLHelpers1.getXMLObjectFromString("<TestIdentityChainRequest/>");
        
        CougarManager cougarManager2 = CougarManager.getInstance();
        HttpCallBean getNewHttpCallBean2 = cougarManager2.getNewHttpCallBean("87.248.113.14");
        cougarManager2 = cougarManager2;
        
        
        cougarManager2.setCougarFaultControllerJMXMBeanAttrbiute("DetailedFaults", "false");
        
        getNewHttpCallBean2.setServiceName("Baseline", "cougarBaseline");
        
        getNewHttpCallBean2.setVersion("v2");
        
        Map map3 = new HashMap();
        map3.put("Username","foo");
        map3.put("Password","bar");
        getNewHttpCallBean2.setAuthCredentials(map3);
        
        getNewHttpCallBean2.setPostObjectForRequestType(createAsDocument1, "SOAP");
        

        Timestamp getTimeAsTimeStamp9 = new Timestamp(System.currentTimeMillis());
        
        cougarManager2.makeSoapCougarHTTPCalls(getNewHttpCallBean2);
        
        XMLHelpers xMLHelpers5 = new XMLHelpers();
        Document createAsDocument11 = xMLHelpers5.getXMLObjectFromString("<response><identities><Ident><principal>PRINCIPAL: Password</principal><credentialName>CREDENTIAL: Password</credentialName><credentialValue>bar</credentialValue></Ident><Ident><principal>PRINCIPAL: Username</principal><credentialName>CREDENTIAL: Username</credentialName><credentialValue>foo</credentialValue></Ident></identities></response>");
        
        Map<String, Object> convertResponseToSOAP12 = cougarManager2.convertResponseToSOAP(createAsDocument11, getNewHttpCallBean2);
        
        HttpResponseBean getResponseObjectsByEnum13 = getNewHttpCallBean2.getResponseObjectsByEnum(com.betfair.testing.utils.cougar.enums.CougarMessageProtocolResponseTypeEnum.SOAP);
        AssertionUtils.multiAssertEquals(convertResponseToSOAP12.get("SOAP"), getResponseObjectsByEnum13.getResponseObject());
        
        Map<String, String> map7 = getResponseObjectsByEnum13.getResponseHeaders();
        AssertionUtils.multiAssertEquals("Password:bar Username:foo", map7.get("Credentials"));
        
        // generalHelpers.pauseTest(1000L);
        
        
        cougarManager2.verifyRequestLogEntriesAfterDate(getTimeAsTimeStamp9, new RequestLogRequirement("2.8", "testIdentityChain") );
        
        cougarManager2.verifyAccessLogEntriesAfterDate(getTimeAsTimeStamp9, new AccessLogRequirement(null, null, "Ok") );
    }

}
