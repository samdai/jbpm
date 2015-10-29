/*
 * Copyright 2013 JBoss by Red Hat.
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

package org.jbpm.executor.impl.wih;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManagerFactory;

import org.jbpm.executor.ExecutorServiceFactory;
import org.jbpm.process.audit.JPAAuditLogService;
import org.jbpm.process.instance.impl.demo.DoNothingWorkItemHandler;
import org.jbpm.runtime.manager.impl.DefaultRegisterableItemsFactory;
import org.jbpm.runtime.manager.impl.jpa.EntityManagerFactoryManager;
import org.jbpm.services.task.audit.service.TaskJPAAuditService;
import org.jbpm.services.task.identity.JBossUserGroupCallbackImpl;
import org.jbpm.test.util.AbstractBaseTest;
import org.jbpm.test.util.TestUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeEnvironment;
import org.kie.api.runtime.manager.RuntimeEnvironmentBuilder;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.manager.RuntimeManagerFactory;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.task.UserGroupCallback;
import org.kie.internal.executor.api.CommandContext;
import org.kie.internal.executor.api.ExecutorService;
import org.kie.internal.io.ResourceFactory;
import org.kie.internal.runtime.manager.RuntimeManagerRegistry;
import org.kie.internal.runtime.manager.context.EmptyContext;

import bitronix.tm.resource.jdbc.PoolingDataSource;

public class CleanupLogCommandWithProcessTest extends AbstractBaseTest {

    private PoolingDataSource pds;
    private UserGroupCallback userGroupCallback;  
    private RuntimeManager manager;
    private ExecutorService executorService;
    private EntityManagerFactory emf = null;
    @Before
    public void setup() {
        TestUtil.cleanupSingletonSessionId();
        pds = TestUtil.setupPoolingDataSource();
        Properties properties= new Properties();
        properties.setProperty("mary", "HR");
        properties.setProperty("john", "HR");
        userGroupCallback = new JBossUserGroupCallbackImpl(properties);
        executorService = buildExecutorService();
    }
    
    @After
    public void teardown() {
        executorService.destroy();
        if (manager != null) {
            RuntimeManagerRegistry.get().remove(manager.getIdentifier());
            manager.close();
        }
        if (emf != null) {
        	emf.close();
        }
        pds.close();
    }

    @Test
    public void testRunProcessWithAsyncHandler() throws Exception {

        RuntimeEnvironment environment = RuntimeEnvironmentBuilder.Factory.get().newDefaultBuilder()
                .userGroupCallback(userGroupCallback)
                .entityManagerFactory(emf)
                .addAsset(ResourceFactory.newClassPathResource("BPMN2-ScriptTask.bpmn2"), ResourceType.BPMN2)
                .registerableItemsFactory(new DefaultRegisterableItemsFactory() {

                    @Override
                    public Map<String, WorkItemHandler> getWorkItemHandlers(RuntimeEngine runtime) {

                        Map<String, WorkItemHandler> handlers = super.getWorkItemHandlers(runtime);
                        handlers.put("async", new DoNothingWorkItemHandler());
                        return handlers;
                    }
                    
                })
                .get();
        
        manager = RuntimeManagerFactory.Factory.get().newSingletonRuntimeManager(environment); 
        assertNotNull(manager);
        
        RuntimeEngine runtime = manager.getRuntimeEngine(EmptyContext.get());
        KieSession ksession = runtime.getKieSession();
        assertNotNull(ksession);  
        
        assertEquals(0, getProcessLogSize("ScriptTask"));
        assertEquals(0, getNodeInstanceLogSize("ScriptTask"));
        assertEquals(0, getTaskLogSize("ScriptTask"));
        assertEquals(0, getVariableLogSize("ScriptTask"));
        
        Date startDate = new Date();
        
        ProcessInstance processInstance = ksession.startProcess("ScriptTask");
        assertEquals(ProcessInstance.STATE_ACTIVE, processInstance.getState());
        
        assertEquals(1, getProcessLogSize("ScriptTask"));
        assertEquals(5, getNodeInstanceLogSize("ScriptTask"));
        assertEquals(0, getTaskLogSize("ScriptTask"));
        assertEquals(0, getVariableLogSize("ScriptTask"));
        
        scheduleLogCleanup(false, true, false, startDate, "ScriptTask", "yyyy-MM-dd", manager.getIdentifier());
        Thread.sleep(5 * 1000);
        System.out.println("Aborting process instance " + processInstance.getId());
        processInstance = runtime.getKieSession().getProcessInstance(processInstance.getId());
        assertNotNull(processInstance);
        
        assertEquals(1, getProcessLogSize("ScriptTask"));
        assertEquals(5, getNodeInstanceLogSize("ScriptTask"));
        assertEquals(0, getTaskLogSize("ScriptTask"));
        assertEquals(0, getVariableLogSize("ScriptTask"));
        
        runtime.getKieSession().abortProcessInstance(processInstance.getId());
        
        processInstance = runtime.getKieSession().getProcessInstance(processInstance.getId());
        assertNull(processInstance);
        
        assertEquals(1, getProcessLogSize("ScriptTask"));
        assertEquals(6, getNodeInstanceLogSize("ScriptTask"));
        assertEquals(0, getTaskLogSize("ScriptTask"));
        assertEquals(0, getVariableLogSize("ScriptTask"));
        
        Thread.sleep(1000);
        
        scheduleLogCleanup(false, false, false, new Date(), "ScriptTask", "yyyy-MM-dd HH:mm:ss", manager.getIdentifier());
        Thread.sleep(5 * 1000);
        
        assertEquals(0, getProcessLogSize("ScriptTask"));
        assertEquals(0, getNodeInstanceLogSize("ScriptTask"));
        assertEquals(0, getTaskLogSize("ScriptTask"));
        assertEquals(0, getVariableLogSize("ScriptTask"));
    }
    
    
    private ExecutorService buildExecutorService() {        
        emf = EntityManagerFactoryManager.get().getOrCreate("org.jbpm.persistence.complete");

        executorService = ExecutorServiceFactory.newExecutorService(emf);
        
        executorService.init();
        
        return executorService;
    }
    
	private void scheduleLogCleanup(boolean skipProcessLog,
			boolean skipTaskLog, boolean skipExecutorLog, Date olderThan,
			String forProcess, String dateFormat, String identifier) {
		CommandContext commandContext = new CommandContext();
		commandContext.setData("EmfName", "org.jbpm.persistence.complete");
		commandContext.setData("SkipProcessLog", String.valueOf(skipProcessLog));
		commandContext.setData("SkipTaskLog", String.valueOf(skipTaskLog));
		commandContext.setData("SkipExecutorLog",String.valueOf(skipExecutorLog));
		commandContext.setData("SingleRun", "true");
		commandContext.setData("OlderThan", new SimpleDateFormat(dateFormat).format(olderThan));
		commandContext.setData("DateFormat", dateFormat);
		commandContext.setData("ForDeployment", identifier);
		// commandContext.setData("OlderThanPeriod", olderThanPeriod);
		commandContext.setData("ForProcess", forProcess);
		executorService.scheduleRequest("org.jbpm.executor.commands.LogCleanupCommand", commandContext);
	}
	
	private int getProcessLogSize(String processId) {
        return new JPAAuditLogService(emf).processInstanceLogQuery()
                .processId(processId)
                .buildQuery()
                .getResultList()
                .size();
    }

    private int getTaskLogSize(String processId) {
        return new TaskJPAAuditService(emf).auditTaskInstanceLogQuery()
                .processId(processId)
                .buildQuery()
                .getResultList()
                .size();
    }
    
    private int getNodeInstanceLogSize(String processId) {
        return new JPAAuditLogService(emf).nodeInstanceLogQuery()
                .processId(processId)
                .buildQuery()
                .getResultList()
                .size();
    }
    
    private int getVariableLogSize(String processId) {
        return new JPAAuditLogService(emf).variableInstanceLogQuery()
                .processId(processId)
                .buildQuery()
                .getResultList()
                .size();
    }
}
