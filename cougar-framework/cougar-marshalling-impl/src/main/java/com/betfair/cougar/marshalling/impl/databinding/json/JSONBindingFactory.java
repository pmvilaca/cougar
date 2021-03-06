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

package com.betfair.cougar.marshalling.impl.databinding.json;

import java.io.IOException;
import java.util.logging.Level;

import com.betfair.cougar.CougarVersion;
import com.betfair.cougar.logging.CougarLogger;
import com.betfair.cougar.logging.CougarLoggingUtils;
import com.betfair.cougar.marshalling.api.databinding.*;
import org.codehaus.jackson.*;
import org.codehaus.jackson.io.NumberInput;
import org.codehaus.jackson.map.DeserializationContext;
import org.codehaus.jackson.map.JsonDeserializer;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.codehaus.jackson.map.deser.StdDeserializer;
import org.codehaus.jackson.map.module.SimpleModule;

public class JSONBindingFactory implements DataBindingFactory {
	private final static CougarLogger logger = CougarLoggingUtils.getLogger(JSONBindingFactory.class);

	private final ObjectMapper objectMapper;
    private final JSONMarshaller marshaller;
    private final JSONUnMarshaller unMarshaller;

	public JSONBindingFactory() {
		logger.log(Level.INFO, "Initialising JSONBindingFactory");
		objectMapper = createBaseObjectMapper();
		marshaller = new JSONMarshaller(objectMapper);
        unMarshaller = new JSONUnMarshaller(objectMapper);
	}
	
	public static ObjectMapper createBaseObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		JSONDateFormat jdf=new JSONDateFormat();
		mapper.getSerializationConfig().setDateFormat(jdf);
		mapper.getSerializationConfig().setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);
		mapper.getDeserializationConfig().setDateFormat(jdf);

        applyNumericRangeBugfixes(mapper);
		return mapper;
	}

    /**
     * Fixes problem in Jackson's StdDeserializer. with _parseInteger and _parseLong.
     * The provided implementation allowed out-of-range numbers to be shoe-horned into types, ignoring under/overflow.
     * E.g. 21474836470 would be deserialized into an Integer as -10.
     * E.g. 92233720368547758080 would be deserialized into a Long as 0.
     */
    private static void applyNumericRangeBugfixes(ObjectMapper mapper) {
        // Create a custom module
        SimpleModule customModule = new SimpleModule("CustomModule", new Version(1, 0, 0, null));
        // Register a deserializer for Integer that overrides default buggy version
        customModule.addDeserializer(Integer.class, new IntegerDeserializer());
        customModule.addDeserializer(int.class, new IntegerDeserializer());
        // Register a deserializer for Long that overrides default buggy version
        customModule.addDeserializer(Long.class, new LongDeserializer());
        customModule.addDeserializer(long.class, new LongDeserializer());
        // Register a deserializer for Byte that overrides default buggy version
        customModule.addDeserializer(Byte.class, new ByteDeserializer());
        customModule.addDeserializer(byte.class, new ByteDeserializer());
        // Add the module to the mapper
        mapper.registerModule(customModule);
    }

	@Override
	public UnMarshaller getUnMarshaller() {
		return unMarshaller;
	}

	@Override
	public Marshaller getMarshaller() {
        return marshaller;
	}

	@Override
	public FaultMarshaller getFaultMarshaller() {
        return marshaller;
	}

    @Override
    public FaultUnMarshaller getFaultUnMarshaller() {
        return unMarshaller;
    }

    /**
     * A deserializer for Integer that properly checks whether the value is within range.
     * Needed because Jackson's StdDeserializer._parseInteger has a bug in it that allows underflow and overflow.
     * This deserializer is a copy of Jackson's own StdDeserializer._parseInteger with the fix applied.
     * When registered with a mapper it overrides the buggy default deserialization of Integer.
     */
    static class IntegerDeserializer extends StdDeserializer<Integer> {
        public IntegerDeserializer() {
            super(Integer.class);
        }
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken t = parser.getCurrentToken();
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) { // coercing should work too
                return rangeCheckedInteger(parser, context);
            }
            if (t == JsonToken.VALUE_STRING) { // let's do implicit re-parse
                String text = parser.getText().trim();
                try {
                    int len = text.length();
                    if (len > 9) {
                        return rangeCheckedInteger(parser, context);
                    }
                    if (len == 0) {
                        return null;
                    }
                    return Integer.valueOf(NumberInput.parseInt(text));
                } catch (IllegalArgumentException iae) {
                    throw context.weirdStringException(_valueClass, "not a valid Integer value");//NOSONAR
                }
            }
            if (t == JsonToken.VALUE_NULL) {
                return null;
            }
            // Otherwise, no can do:
            throw context.mappingException(_valueClass);
        }
        private Integer rangeCheckedInteger(JsonParser parser, DeserializationContext context)
        throws IOException {
            long l = parser.getLongValue();
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw context.weirdStringException(
                    _valueClass,
                    "Over/underflow: numeric value (" + l +") out of range of Integer ("
                        + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE + ")"
                );
            }
            return Integer.valueOf((int) l);
        }
    }

    /**
     * A deserializer for Long that properly checks whether the value is within range.
     * Needed because Jackson's StdDeserializer._parseLong has a bug in it that allows underflow and overflow.
     * This deserializer is a copy of Jackson's own StdDeserializer._parseLong with the fix applied.
     * When registered with a mapper it overrides the buggy default deserialization of Long.
     */
    static class LongDeserializer extends StdDeserializer<Long> {
        public LongDeserializer() {
            super(Long.class);
        }
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken t = parser.getCurrentToken();
            // it should be ok to coerce (although may fail, too)
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT || t == JsonToken.VALUE_STRING) {
                return rangeCheckedLong(parser, context);
            }
            if (t == JsonToken.VALUE_NULL) {
                return null;
            }
            // Otherwise, no can do:
            throw context.mappingException(_valueClass);
        }
        private Long rangeCheckedLong(JsonParser parser, DeserializationContext context)
        throws IOException {
            String text = parser.getText().trim();
            if (text.length() == 0) {
                return null;
            }
            try {
                return Long.valueOf(NumberInput.parseLong(text));
            }
            catch (Exception e) {
                throw context.weirdStringException(//NOSONAR
                    _valueClass,
                    "Over/underflow: numeric value (" + text +") out of range of Long ("
                        + Long.MIN_VALUE + " to " + Long.MAX_VALUE + ")"
                );
            }
        }
    }

    /**
     * A deserializer for Long that properly checks whether the value is within range.
     * Needed because Jackson's StdDeserializer._parseByte uses JsonParser.getByteValue() which is too permissive,
     * allowing values in the range -128 to +255, but we want byte to be -128 to +127
     * See also: [JACKSON-804]
     * When registered with a mapper it overrides the buggy default deserialization of Long.
     */
    static class ByteDeserializer extends StdDeserializer<Byte> {
        public ByteDeserializer() {
            super(Byte.class);
        }

        @Override
        public Byte deserialize(JsonParser jp, DeserializationContext ctxt)
                throws IOException, JsonProcessingException
        {
            return _parseByte(jp, ctxt);
        }
        @Override
        protected Byte _parseByte(JsonParser jp, DeserializationContext ctxt) throws IOException {
            JsonToken t = jp.getCurrentToken();
            Integer value = null;
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) { // coercing should work too
                value = jp.getIntValue();
            }
            else if (t == JsonToken.VALUE_STRING) { // let's do implicit re-parse
                String text = jp.getText().trim();
                try {
                    int len = text.length();
                    if (len == 0) {
                        return getEmptyValue();
                    }
                    value = NumberInput.parseInt(text);
                } catch (IllegalArgumentException iae) {
                    throw ctxt.weirdStringException(_valueClass, "not a valid Byte value");//NOSONAR
                }
            }
            if (value != null) {
                // So far so good: but does it fit?
                if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                    throw ctxt.weirdStringException(_valueClass, "overflow, value can not be represented as 8-bit value");
                }
                return (byte) (int) value;
            }
            if (t == JsonToken.VALUE_NULL) {
                return getNullValue();
            }
            throw ctxt.mappingException(_valueClass, t);
        }
    }
}