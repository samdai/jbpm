/**
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jbpm.process.audit;

import static org.jbpm.persistence.util.PersistenceUtil.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.jbpm.process.audit.AuditLoggerFactory.Type;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.KieSession;
import org.kie.internal.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class tests the following classes: 
 * <ul>
 * <li>JPAWorkingMemoryDbLogger</li>
 * <li>AuditLogService</li>
 * </ul>
 */
public class AuditLogServiceTest extends AbstractAuditLogServiceTest {

    private HashMap<String, Object> context;
    private static final Logger logger = LoggerFactory.getLogger(AuditLogServiceTest.class);
    
    private KieSession session;
    private AuditLogService auditLogService; 


    @Before
    public void setUp() throws Exception {
        context = setupWithPoolingDataSource(JBPM_PERSISTENCE_UNIT_NAME);
        
        // load the process
        KnowledgeBase kbase = createKnowledgeBase();
        // create a new session
        Environment env = createEnvironment(context);
        session = createKieSession(kbase, env);
       
        // working memory logger
        AbstractAuditLogger dblogger = AuditLoggerFactory.newInstance(Type.JPA, session, null);
        assertNotNull(dblogger);
        assertTrue(dblogger instanceof JPAWorkingMemoryDbLogger);
        
        auditLogService = new JPAAuditLogService(env);
    }

    @After
    public void tearDown() throws Exception {
        cleanUp(context);
        session.dispose();
        session = null;
        auditLogService = null;
        System.clearProperty("org.jbpm.var.log.length");
    }

    @Test
    public void testLogger1() throws Exception {
        runTestLogger1(session, auditLogService);
    }
    
    @Test
    public void testLogger2() {
        runTestLogger2(session, auditLogService);
    }
    
    @Test
    public void testLogger3() {
        runTestLogger3(session, auditLogService);
    }
    
    @Test
    public void testLogger4() throws Exception {
        runTestLogger4(session, auditLogService);
    }
    
    @Test
    public void testLogger4LargeVariable() throws Exception {
        runTestLogger4LargeVariable(session, auditLogService);
    }
    
    
    @Test
    public void testLogger5() throws Exception { 
        runTestLogger5(session, auditLogService);
    }
    
    @Test
    public void testLoggerWithCustomVariableLogLength() throws Exception { 
    	runTestLoggerWithCustomVariableLogLength(session, auditLogService);
    }

}