/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oozie.util;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.MapFile;
import org.apache.oozie.test.XTestCase;

public class TestXConfiguration extends XTestCase {

    public void testFromStream() throws Exception {
        String configPath = "test-oozie-default.xml";
        InputStream is = IOUtils.getResourceAsStream(configPath, -1);
        XConfiguration conf = new XConfiguration(is);
        assertEquals("DEFAULT", conf.get("oozie.dummy"));
    }

    public void testFromReader() throws Exception {
        String configPath = "test-oozie-default.xml";
        Reader reader = IOUtils.getResourceAsReader(configPath, -1);
        XConfiguration conf = new XConfiguration(reader);
        assertEquals("DEFAULT", conf.get("oozie.dummy"));
    }

    public void testInvalidParsing() throws Exception {
        try {
            new XConfiguration(new StringReader("<configurationx></configurationx>"));
            fail();
        }
        catch (IOException ex) {
            //NOP
        }
        catch (Throwable ex) {
            fail();
        }
    }

    public void testCopy() throws Exception {
        Configuration srcConf = new Configuration(false);
        Configuration targetConf = new Configuration(false);

        srcConf.set("testParameter1", "valueFromSource");
        srcConf.set("testParameter2", "valueFromSource");

        targetConf.set("testParameter2", "valueFromTarget");
        targetConf.set("testParameter3", "valueFromTarget");

        XConfiguration.copy(srcConf, targetConf);

        assertEquals(targetConf.get("testParameter1"), "valueFromSource");
        assertEquals(targetConf.get("testParameter2"), "valueFromSource");
        assertEquals(targetConf.get("testParameter3"), "valueFromTarget");

    }

    public void testInjectDefaults() throws Exception {
        Configuration srcConf = new Configuration(false);
        Configuration targetConf = new Configuration(false);

        srcConf.set("testParameter1", "valueFromSource");
        srcConf.set("testParameter2", "valueFromSource");

        targetConf.set("testParameter2", "originalValueFromTarget");
        targetConf.set("testParameter3", "originalValueFromTarget");

        XConfiguration.injectDefaults(srcConf, targetConf);

        assertEquals(targetConf.get("testParameter1"), "valueFromSource");
        assertEquals(targetConf.get("testParameter2"), "originalValueFromTarget");
        assertEquals(targetConf.get("testParameter3"), "originalValueFromTarget");

        assertEquals(srcConf.get("testParameter1"), "valueFromSource");
        assertEquals(srcConf.get("testParameter2"), "valueFromSource");
        assertNull(srcConf.get("testParameter3"));
    }

    public void testTrim() {
        Configuration conf = new XConfiguration();
        conf.set("a", " A ");
        conf.set("b", "B");
        conf = XConfiguration.trim(conf);
        assertEquals("A", conf.get("a"));
        assertEquals("B", conf.get("b"));
    }

}