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

// Originally from UpdatedComponentTests/StandardValidation/REST/Rest_Post_MissingMandatory_MapOfComplex.xls;
package com.betfair.cougar.tests.updatedcomponenttests.standardvalidation.rest;

import com.betfair.testing.utils.cougar.misc.XMLHelpers;
import com.betfair.testing.utils.cougar.assertions.AssertionUtils;
import com.betfair.testing.utils.cougar.beans.HttpCallBean;
import com.betfair.testing.utils.cougar.beans.HttpResponseBean;
import com.betfair.testing.utils.cougar.enums.CougarMessageProtocolRequestTypeEnum;
import com.betfair.testing.utils.cougar.manager.AccessLogRequirement;
import com.betfair.testing.utils.cougar.manager.CougarManager;

import org.testng.annotations.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.util.Map;

/**
 * Ensure that the correct fault is returned when, a Rest(JSON/XML) Post operation is performed against Cougar, passing a Map of string:complex object in the post body, where the complex object  contained in the map has a mandatory field missing
 */
public class RestPostMissingMandatoryMapOfComplexTest {
    @Test
    public void doTest() throws Exception {
        // Set up the Http Call Bean to make the request
        CougarManager cougarManager1 = CougarManager.getInstance();
        HttpCallBean getNewHttpCallBean1 = cougarManager1.getNewHttpCallBean("87.248.113.14");
        cougarManager1 = cougarManager1;
        // Turn detailed faults off
        cougarManager1.setCougarFaultControllerJMXMBeanAttrbiute("DetailedFaults", "false");
        
        getNewHttpCallBean1.setOperationName("mapOfComplexOperation");
        
        getNewHttpCallBean1.setServiceName("baseline", "cougarBaseline");
        
        getNewHttpCallBean1.setVersion("v2");
        // Set the body param to a map of complex objects where one of the entries is missing mandatory fields
        getNewHttpCallBean1.setRestPostQueryObjects(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream("<inputMap><entry key=\"aaa\"><ComplexObject/></entry></inputMap>".getBytes())));
        // Get current time for getting log entries later

        Timestamp getTimeAsTimeStamp7 = new Timestamp(System.currentTimeMillis());
        // Make the 4 REST calls to the operation
        cougarManager1.makeRestCougarHTTPCalls(getNewHttpCallBean1);
        // Create the expected response as an XML document (Fault)
        XMLHelpers xMLHelpers3 = new XMLHelpers();
        Document createAsDocument12 = xMLHelpers3.getXMLObjectFromString("<fault><faultcode>Client</faultcode><faultstring>DSC-0018</faultstring><detail/></fault>");
        // Convert the expected response to REST types for comparison with actual responses
        Map<CougarMessageProtocolRequestTypeEnum, Object> convertResponseToRestTypes13 = cougarManager1.convertResponseToRestTypes(createAsDocument12, getNewHttpCallBean1);
        // Check the 4 responses are as expected
        HttpResponseBean response4 = getNewHttpCallBean1.getResponseObjectsByEnum(com.betfair.testing.utils.cougar.enums.CougarMessageProtocolResponseTypeEnum.RESTXMLXML);
        AssertionUtils.multiAssertEquals(convertResponseToRestTypes13.get(CougarMessageProtocolRequestTypeEnum.RESTXML), response4.getResponseObject());
        AssertionUtils.multiAssertEquals((int) 400, response4.getHttpStatusCode());
        AssertionUtils.multiAssertEquals("Bad Request", response4.getHttpStatusText());
        
        HttpResponseBean response5 = getNewHttpCallBean1.getResponseObjectsByEnum(com.betfair.testing.utils.cougar.enums.CougarMessageProtocolResponseTypeEnum.RESTJSONJSON);
        AssertionUtils.multiAssertEquals(convertResponseToRestTypes13.get(CougarMessageProtocolRequestTypeEnum.RESTJSON), response5.getResponseObject());
        AssertionUtils.multiAssertEquals((int) 400, response5.getHttpStatusCode());
        AssertionUtils.multiAssertEquals("Bad Request", response5.getHttpStatusText());
        
        HttpResponseBean response6 = getNewHttpCallBean1.getResponseObjectsByEnum(com.betfair.testing.utils.cougar.enums.CougarMessageProtocolResponseTypeEnum.RESTXMLJSON);
        AssertionUtils.multiAssertEquals(convertResponseToRestTypes13.get(CougarMessageProtocolRequestTypeEnum.RESTJSON), response6.getResponseObject());
        AssertionUtils.multiAssertEquals((int) 400, response6.getHttpStatusCode());
        AssertionUtils.multiAssertEquals("Bad Request", response6.getHttpStatusText());
        
        HttpResponseBean response7 = getNewHttpCallBean1.getResponseObjectsByEnum(com.betfair.testing.utils.cougar.enums.CougarMessageProtocolResponseTypeEnum.RESTJSONXML);
        AssertionUtils.multiAssertEquals(convertResponseToRestTypes13.get(CougarMessageProtocolRequestTypeEnum.RESTXML), response7.getResponseObject());
        AssertionUtils.multiAssertEquals((int) 400, response7.getHttpStatusCode());
        AssertionUtils.multiAssertEquals("Bad Request", response7.getHttpStatusText());
        
        // generalHelpers.pauseTest(500L);
        // Check the log entries are as expected
        
        cougarManager1.verifyAccessLogEntriesAfterDate(getTimeAsTimeStamp7, new AccessLogRequirement("87.248.113.14", "/cougarBaseline/v2/mapOfComplexOperation", "BadRequest"),new AccessLogRequirement("87.248.113.14", "/cougarBaseline/v2/mapOfComplexOperation", "BadRequest"),new AccessLogRequirement("87.248.113.14", "/cougarBaseline/v2/mapOfComplexOperation", "BadRequest"),new AccessLogRequirement("87.248.113.14", "/cougarBaseline/v2/mapOfComplexOperation", "BadRequest") );
    }

}
