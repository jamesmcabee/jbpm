package org.jbpm.test.persistence.migration;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.drools.core.command.impl.GenericCommand;
import org.drools.core.command.impl.KnowledgeCommandContext;
import org.jbpm.persistence.processinstance.ProcessInstanceInfo;
import org.jbpm.test.JbpmJUnitBaseTestCase;
import org.jbpm.workflow.instance.WorkflowProcessInstance;
import org.jbpm.workflow.instance.WorkflowProcessInstanceUpgrader;
import org.jbpm.workflow.instance.node.WorkItemNodeInstance;
import org.junit.Before;
import org.junit.Test;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.manager.audit.VariableInstanceLog;
import org.kie.api.runtime.process.NodeInstance;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.runtime.process.WorkItem;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.runtime.process.WorkItemManager;
import org.kie.api.task.TaskService;
import org.kie.api.task.model.TaskSummary;

import bitronix.tm.BitronixTransactionManager;
import bitronix.tm.TransactionManagerServices;

public class ProcessInstanceMigrationTest extends JbpmJUnitBaseTestCase {

    public ProcessInstanceMigrationTest() {
        super(true, true);
    }

    private RuntimeManager manager;
    private RuntimeEngine engine;
    private KieSession ksession;
    private TaskService taskService;
    private BitronixTransactionManager transactionManager;

    @Before
    public void init() throws Exception {
        manager = createRuntimeManager("migration/sample.bpmn2", "migration/sample2.bpmn2",
                "migration/BPMN2-ProcessVersion-3.bpmn2", "migration/BPMN2-ProcessVersion-4.bpmn2",
                "migration/sample3.bpmn2", "migration/sample4.bpmn2",
                "migration/subprocess1.bpmn2", "migration/subprocess2.bpmn2");
        engine = manager.getRuntimeEngine(null);
        ksession = engine.getKieSession();
        taskService = engine.getTaskService();

        transactionManager = TransactionManagerServices.getTransactionManager();
        transactionManager.setTransactionTimeout(3600); // longer timeout
                                                        // for a debugger
    }
    
    @Test
    public void testProcessInstanceMigration() throws Exception {
        ProcessInstance p = ksession.startProcess("com.sample.bpmn.migration");
        long pid = p.getId();

        assertEquals("com.sample.bpmn.migration", ksession.getProcessInstance(pid).getProcessId());

        // let john execute Task 1
        List<TaskSummary> list = assertTaskAssignedTo("john");

        // upgrade to version to of the process
        UpgradeCommand c = new UpgradeCommand(pid, null, "com.sample.bpmn.migration2");
        ksession.execute(c);

        completeTask(list.get(0));

        // in second version of the process second user task is for mary while
        // for first version it's for john
        list = assertTaskAssignedTo("mary");

        assertDefinitionChanged(pid, "com.sample.bpmn.migration2", false);
    }

    

    @Test
    public void testProcessInstanceMigrationWithGatewaysAndSameUniqueId() throws Exception {
            
        PersistenceWorkItemHandler handler = new PersistenceWorkItemHandler();
        ksession.getWorkItemManager().registerWorkItemHandler("Human Task", handler);
        
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("x", 5);
        
        WorkflowProcessInstance processInstance = (WorkflowProcessInstance) ksession.startProcess("com.sample.BPMN2ProcessVersion3", params);
        long pid = processInstance.getId();
        
        assertEquals(1, processInstance.getNodeInstances().size());
        
        Map<String, String> mapping = new HashMap<String, String>();
        mapping.put("UserTask", "StartTask");
    
        
        // upgrade to version to of the process
        ExplicitUpgradeCommand c = new ExplicitUpgradeCommand(pid, mapping, "com.sample.BPMN2ProcessVersion4");
        ksession.execute(c);
    
        assertEquals("com.sample.BPMN2ProcessVersion4", ksession.getProcessInstance(pid).getProcessId());
        
        assertEquals(1, processInstance.getNodeInstances().size());
        
        NodeInstance current = processInstance.getNodeInstances().iterator().next();
                
        handler.completeWorkItem(((WorkItemNodeInstance)current).getWorkItem(), ksession.getWorkItemManager());
        
        handler.completeWorkItem(handler.getWorkItem("FirstPath"), ksession.getWorkItemManager());
        handler.completeWorkItem(handler.getWorkItem("SecondPath"), ksession.getWorkItemManager());
        
        
        List<? extends VariableInstanceLog> vars = engine.getAuditService().findVariableInstances(pid, "x");
        assertNotNull(vars);
        assertEquals(2, vars.size());
        assertEquals("10", vars.get(1).getValue());
        
        assertDefinitionChanged(pid, "com.sample.BPMN2ProcessVersion4", true);
        
    }
    
    @Test
    public void testProcessInstanceMigrationExplicit() throws Exception {
        ProcessInstance p = ksession.startProcess("com.sample.bpmn.migration");
        long pid = p.getId();

        assertEquals("com.sample.bpmn.migration", ksession.getProcessInstance(pid).getProcessId());

        List<TaskSummary> list = assertTaskAssignedTo("john");
        
        assertEquals(ProcessInstance.STATE_ACTIVE, p.getState());
        Map<String, String> mapping = new HashMap<String, String>();
        mapping.put("Task 1", "Task 2");

        // upgrade to version to of the process
        ExplicitUpgradeCommand c = new ExplicitUpgradeCommand(pid, mapping, "com.sample.bpmn.migration2");
        ksession.execute(c);
        
        completeTask(list.get(0));

        assertDefinitionChanged(pid, "com.sample.bpmn.migration2", true);
    }
    
    @Test
    public void testProcessInstanceMigrationExplicitSubprocesses() throws Exception {
        
        PersistenceWorkItemHandler handler = new PersistenceWorkItemHandler();
        ksession.getWorkItemManager().registerWorkItemHandler("Human Task", handler);
        
        WorkflowProcessInstance processInstance = (WorkflowProcessInstance) ksession.startProcess("com.sample.bpmn.migration3");
        long pid = processInstance.getId();

        assertEquals("com.sample.bpmn.migration3", ksession.getProcessInstance(pid).getProcessId());
        
        assertEquals(ProcessInstance.STATE_ACTIVE, processInstance.getState());
        Map<String, String> mapping = new HashMap<String, String>();
        mapping.put("ForJohn", "ForMary");

        ExplicitUpgradeCommand c = new ExplicitUpgradeCommand(pid, mapping, "com.sample.bpmn.migration4");
        ksession.execute(c);
        
        assertEquals("com.sample.bpmn.migration4", ksession.getProcessInstance(pid).getProcessId());
        
        handler.completeWorkItem(handler.getWorkItem("ForJohn"), ksession.getWorkItemManager());
        handler.completeWorkItem(handler.getWorkItem("ForBill"), ksession.getWorkItemManager());
        
        assertDefinitionChanged(pid, "com.sample.bpmn.migration4", true);
    }
    
    @Test
    public void testProcessInstanceMigrationImplicitSubprocess() throws Exception {
        ProcessInstance p = ksession.startProcess("com.sample.bpmn.migration.subprocess1");
        long pid = p.getId();

        assertEquals("com.sample.bpmn.migration.subprocess1", ksession.getProcessInstance(pid).getProcessId());

        List<TaskSummary> list = assertTaskAssignedTo("john");

        // upgrade to version to of the process
        UpgradeCommand c = new UpgradeCommand(pid, null, "com.sample.bpmn.migration.subprocess2");
        ksession.execute(c);

        completeTask(list.get(0));

        // in second version of the process second user task is for mary while
        // for first version it's for john
        list = assertTaskAssignedTo("mary");

        assertDefinitionChanged(pid, "com.sample.bpmn.migration.subprocess2", false);
    }
    
    @Test
    public void testProcessInstanceMigrationExplicitBack() throws Exception {
        ProcessInstance p = ksession.startProcess("com.sample.bpmn.migration.subprocess1");
        long pid = p.getId();

        assertEquals("com.sample.bpmn.migration.subprocess1", ksession.getProcessInstance(pid).getProcessId());

        List<TaskSummary> list = assertTaskAssignedTo("john");
        completeTask(list.get(0));

        Map<String, String> mapping = new HashMap<String, String>();
        mapping.put("ForJohn2", "ForJohn1");
        
        ExplicitUpgradeCommand c = new ExplicitUpgradeCommand(pid, mapping, "com.sample.bpmn.migration.subprocess2");
        ksession.execute(c);

        list = assertTaskAssignedTo("john");
        completeTask(list.get(0));
        
        list = assertTaskAssignedTo("mary");

        assertDefinitionChanged(pid, "com.sample.bpmn.migration.subprocess2", false);
    }
    
    
    private class PersistenceWorkItemHandler implements WorkItemHandler {
    
        private List<WorkItem> workItems = new ArrayList<WorkItem>();
        
        
        public WorkItem getWorkItem(String nodeName) {
            for(WorkItem item : workItems) {
                if(((String) item.getParameter("NodeName")).compareTo(nodeName) == 0) {
                    return item;
                }
            }
            
            return null;
        }
        
        public void completeWorkItem(WorkItem workItem, WorkItemManager manager) {
            manager.completeWorkItem(workItem.getId(), null);
            workItems.remove(workItem);
        }
        
        @Override
        public void executeWorkItem(WorkItem workItem, WorkItemManager manager) {
            workItems.add(workItem);
        }
        
        @Override
        public void abortWorkItem(WorkItem workItem, WorkItemManager manager) {
            workItems.remove(workItem);
            manager.abortWorkItem(workItem.getId());
        }
    }

    private static class UpgradeCommand implements GenericCommand<Object> {

        private static final long serialVersionUID = -626809842544969669L;

        private long pid;
        private Map<String, Long> mapping;
        private String toProcessId;

        private UpgradeCommand(long pid, Map<String, Long> mapping, String toProcessId) {
            this.pid = pid;
            this.mapping = mapping;
            this.toProcessId = toProcessId;
        }

        public Object execute(org.kie.internal.command.Context arg0) {
            KieSession ksession = ((KnowledgeCommandContext) arg0).getKieSession();

            WorkflowProcessInstanceUpgrader.upgradeProcessInstance(ksession,
                    pid, toProcessId, mapping);

            return null;

        }
    }

    private static class ExplicitUpgradeCommand implements GenericCommand<Object> {
        private static final long serialVersionUID = 8673518721648293556L;
        
        private long pid;
        private Map<String, String> mapping;
        private String toProcessId;

        private ExplicitUpgradeCommand(long pid, Map<String, String> mapping, String toProcessId) {
            this.pid = pid;
            this.mapping = mapping;
            this.toProcessId = toProcessId;
        }

        public Object execute(org.kie.internal.command.Context arg0) {
            KieSession ksession = ((KnowledgeCommandContext) arg0).getKieSession();

            WorkflowProcessInstanceUpgrader.upgradeProcessInstanceByNodeNames(
                    ksession,
                    pid,
                    toProcessId,
                    mapping);

            return null;

        }
    }
    
    private List<TaskSummary> assertTaskAssignedTo(String user) {
        List<TaskSummary> list = taskService.getTasksAssignedAsPotentialOwner(user, "en-UK");
        assertNotNull(list);
        assertEquals(1, list.size());
        return list;
    }
    
    private void completeTask(TaskSummary task) {
        taskService.start(task.getId(), "john");
        taskService.complete(task.getId(), "john", null);
    }
    
    private void assertDefinitionChanged(long pid, String sPid, boolean complete) throws InterruptedException {
        if(!complete) {
            assertEquals(sPid, ksession.getProcessInstance(pid).getProcessId());
        }

        EntityManager em = getEmf().createEntityManager();
        Query query = em.createQuery(
                        "select p from ProcessInstanceInfo p where p.processInstanceId = :pid")
                .setParameter("pid", pid);
        List<ProcessInstanceInfo> found = query.getResultList();

        int count = (complete) ? 0 : 1;
        
        assertNotNull(found);
        assertEquals(count, found.size());

        if(!complete) {
            ProcessInstanceInfo instance = found.get(0);
            assertEquals(sPid, instance.getProcessId());
        }

        Thread.sleep(400);

        manager.disposeRuntimeEngine(engine);
    }
}
