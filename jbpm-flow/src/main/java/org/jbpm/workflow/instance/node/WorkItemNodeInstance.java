/**
 * Copyright 2005 JBoss Inc
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

package org.jbpm.workflow.instance.node;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.drools.WorkItemHandlerNotFoundException;
import org.drools.definition.process.Node;
import org.drools.process.core.Work;
import org.drools.process.instance.WorkItem;
import org.drools.process.instance.WorkItemManager;
import org.drools.process.instance.impl.WorkItemImpl;
import org.drools.runtime.KnowledgeRuntime;
import org.drools.runtime.process.EventListener;
import org.drools.runtime.process.NodeInstance;
import org.jbpm.process.core.context.variable.VariableScope;
import org.jbpm.process.instance.ProcessInstance;
import org.jbpm.process.instance.context.variable.VariableScopeInstance;
import org.jbpm.process.instance.impl.XPATHExpressionModifier;
import org.jbpm.workflow.core.node.Assignment;
import org.jbpm.workflow.core.node.DataAssociation;
import org.jbpm.workflow.core.node.WorkItemNode;
import org.jbpm.workflow.instance.impl.NodeInstanceResolverFactory;
import org.jbpm.workflow.instance.impl.WorkItemResolverFactory;
import org.mvel2.MVEL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;



/**
 * Runtime counterpart of a work item node.
 * 
 * @author <a href="mailto:kris_verlaenen@hotmail.com">Kris Verlaenen</a>
 */
public class WorkItemNodeInstance extends StateBasedNodeInstance implements EventListener {
    
	protected static final Logger _logger = LoggerFactory.getLogger(WorkItemNodeInstance.class);
    
    protected static final Logger _assignmentsLogger = LoggerFactory.getLogger("org.jbpm.xpath");

    private static final long serialVersionUID = 510l;
    private static final Pattern PARAMETER_MATCHER = Pattern.compile("#\\{(\\S+)\\}", Pattern.DOTALL);
    
    private long workItemId = -1;
    protected transient WorkItem workItem;
    
    protected static String serializeXML(org.w3c.dom.Node node) {
        if (node == null) {
            return null;
        }
        try {
            StringWriter writer = new StringWriter();
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer serializer = tf.newTransformer();
            serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            serializer.setOutputProperty(OutputKeys.INDENT, "yes");
            serializer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StreamResult streamResult = new StreamResult(writer);
            serializer.transform(new DOMSource(node), streamResult);
            return writer.toString();
        } catch (TransformerConfigurationException e) {
            _logger.error(e.getMessage(), e);
        } catch (TransformerException e) {
            _logger.error(e.getMessage(), e);
        }
        return null;
    }
    
    protected WorkItemNode getWorkItemNode() {
        return (WorkItemNode) getNode();
    }
    
    public WorkItem getWorkItem() {
        if (workItem == null && workItemId >= 0) {
            workItem = ((WorkItemManager) ((ProcessInstance) getProcessInstance())
                .getKnowledgeRuntime().getWorkItemManager()).getWorkItem(workItemId);
        }
        return workItem;
    }
    
    public long getWorkItemId() {
        return workItemId;
    }
    
    public void internalSetWorkItemId(long workItemId) {
        this.workItemId = workItemId;
    }
    
    public void internalSetWorkItem(WorkItem workItem) {
        this.workItem = workItem;
    }
    
    public boolean isInversionOfControl() {
        // TODO
        return false;
    }

    public void internalTrigger(final NodeInstance from, String type) {
        super.internalTrigger(from, type);
        // TODO this should be included for ruleflow only, not for BPEL
//        if (!Node.CONNECTION_DEFAULT_TYPE.equals(type)) {
//            throw new IllegalArgumentException(
//                "A WorkItemNode only accepts default incoming connections!");
//        }
        WorkItemNode workItemNode = getWorkItemNode();
        createWorkItem(workItemNode);
        if(workItem.getId() > 0) {
        	workItemId = workItem.getId(); 
        }
        if (workItemNode.isWaitForCompletion()) {
            addWorkItemListener();
        }
        if (isInversionOfControl()) {
            ((ProcessInstance) getProcessInstance()).getKnowledgeRuntime()
                .update(((ProcessInstance) getProcessInstance()).getKnowledgeRuntime().getFactHandle(this), this);
        } else {
            try {
                ((WorkItemManager) ((ProcessInstance) getProcessInstance())
                    .getKnowledgeRuntime().getWorkItemManager()).internalExecuteWorkItem(
                        (org.drools.process.instance.WorkItem) workItem);
            } catch (WorkItemHandlerNotFoundException wihnfe){
                getProcessInstance().setState( ProcessInstance.STATE_ABORTED );
                throw wihnfe;
            }
        }
        if (!workItemNode.isWaitForCompletion()) {
            triggerCompleted();
        }
    }    

    protected WorkItem createWorkItem(WorkItemNode workItemNode) {
        Work work = workItemNode.getWork();
        workItem = new WorkItemImpl();
        ((WorkItem) workItem).setName(work.getName());
        ((WorkItem) workItem).setProcessInstanceId(getProcessInstance().getId());
        ((WorkItem) workItem).setParameters(new HashMap<String, Object>(work.getParameters()));
        for (Iterator<DataAssociation> iterator = workItemNode.getInAssociations().iterator(); iterator.hasNext(); ) {
            DataAssociation association = iterator.next();
            if(association.getAssignments() == null) {
                Object parameterValue = null;
                VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
                resolveContextInstance(VariableScope.VARIABLE_SCOPE, association.getSources().get(0));
                if (variableScopeInstance != null) {
                    parameterValue = variableScopeInstance.getVariable(association.getSources().get(0));
                } else {
                    try {
                        parameterValue = MVEL.eval(association.getSources().get(0), new NodeInstanceResolverFactory(this));
                    } catch (Throwable t) {
                        System.err.println("Could not find variable scope for variable " + association.getSources().get(0));
                        System.err.println("when trying to execute Work Item " + work.getName());
                        System.err.println("Continuing without setting parameter.");
                        throw new RuntimeException("Could not find variable scope for variable " + association.getSources().get(0));
                    }
                }
                if (parameterValue != null) {
                    ((WorkItem) workItem).setParameter(association.getTarget(), parameterValue);
                }
            }
            else {
                String source = association.getSources().get(0);
                String target = association.getTarget();
                try {
                    for(Iterator<Assignment> it = association.getAssignments().iterator(); it.hasNext(); ) {
                        handleAssignment(it.next(), source, target, true);
                    }
                }
                catch(XPathExpressionException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } catch (ParserConfigurationException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } catch (DOMException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } catch (TransformerException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }
        }
        
        for (Map.Entry<String, Object> entry: workItem.getParameters().entrySet()) {
            if (entry.getValue() instanceof String) {
                String s = (String) entry.getValue();
                Map<String, String> replacements = new HashMap<String, String>();
                Matcher matcher = PARAMETER_MATCHER.matcher(s);
                while (matcher.find()) {
                    String paramName = matcher.group(1);
                    if (replacements.get(paramName) == null) {
                        VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
                            resolveContextInstance(VariableScope.VARIABLE_SCOPE, paramName);
                        if (variableScopeInstance != null) {
                            Object variableValue = variableScopeInstance.getVariable(paramName);
                            String variableValueString = variableValue == null ? "" : variableValue.toString(); 
                            replacements.put(paramName, variableValueString);
                        } else {
                            try {
                                Object variableValue = MVEL.eval(paramName, new NodeInstanceResolverFactory(this));
                                String variableValueString = variableValue == null ? "" : variableValue.toString();
                                replacements.put(paramName, variableValueString);
                            } catch (Throwable t) {
                                System.err.println("Could not find variable scope for variable " + paramName);
                                System.err.println("when trying to replace variable in string for Work Item " + work.getName());
                                System.err.println("Continuing without setting parameter.");
                            }
                        }
                    }
                }
                for (Map.Entry<String, String> replacement: replacements.entrySet()) {
                    s = s.replace("#{" + replacement.getKey() + "}", replacement.getValue());
                }
                ((WorkItem) workItem).setParameter(entry.getKey(), s);
            }
        }
        ((org.drools.process.instance.WorkItemManager) 
                ((ProcessInstance) getProcessInstance()).getKnowledgeRuntime().getWorkItemManager()).internalAddWorkItem(workItem);
        return workItem;
    }

    protected void handleAssignment(Assignment assignment, String sourceExpr, String targetExpr, boolean isInput) throws XPathExpressionException, DOMException, TransformerException, ParserConfigurationException {
        String from = assignment.getFrom();
        String to = assignment.getTo();

        XPathFactory factory = XPathFactory.newInstance();
        XPath xpathFrom = factory.newXPath();

        XPathExpression exprFrom = xpathFrom.compile(from);

        XPath xpathTo = factory.newXPath();

        XPathExpression exprTo = xpathTo.compile(to);

        Object target = null;
        Object source = null;
        
        
        if (isInput) {
            VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
            resolveContextInstance(VariableScope.VARIABLE_SCOPE, sourceExpr);
            source = variableScopeInstance.getVariable(sourceExpr);
            target = ((WorkItem) workItem).getParameter(targetExpr);
        } else {
            VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
            resolveContextInstance(VariableScope.VARIABLE_SCOPE, targetExpr);
            target = variableScopeInstance.getVariable(targetExpr);
            source = ((WorkItem) workItem).getResult(sourceExpr);
        }
        
        if (_assignmentsLogger.isDebugEnabled()) {
            //let's make noise about this assignment
            _assignmentsLogger.debug("========== ASSIGN ==========");
            _assignmentsLogger.debug("Assignment between '" + from + "' with '"+ sourceExpr +"' and '" + to + "' with '" + targetExpr +"'");
            _assignmentsLogger.debug("========== SOURCE ==========");
            if (source instanceof org.w3c.dom.Node) {
            	_assignmentsLogger.debug(serializeXML((org.w3c.dom.Node) source));
            } else {
            	_assignmentsLogger.debug(String.valueOf(source));
            }
            _assignmentsLogger.debug("============================");
            _assignmentsLogger.debug("========== TARGET ==========");
            if (target instanceof org.w3c.dom.Node) {
            	_assignmentsLogger.debug(serializeXML((org.w3c.dom.Node) target));
            } else {
            	_assignmentsLogger.debug(String.valueOf(target));
            }
            _assignmentsLogger.debug("============================");
        }
        
        Object targetElem = null;
        
        XPATHExpressionModifier modifier = new XPATHExpressionModifier();
        // modify the tree, returning the root node
        target = modifier.insertMissingData(to, (org.w3c.dom.Node) target);

        // now pick the leaf for this operation
        if (target != null) {
            org.w3c.dom.Node parent = null;
//               if(isInput) {
                parent = ((org.w3c.dom.Node) target).getParentNode();
//               }
//               else {
//                parent = (org.w3c.dom.Node) target;
//               }
                
                
            targetElem = exprTo.evaluate(parent, XPathConstants.NODE);
            
            if (targetElem == null) {
                throw new RuntimeException("Nothing was selected by the to expression " + to + " on " + targetExpr);
            }
        }
        NodeList nl = null;
        if (source instanceof org.w3c.dom.Node) {
             nl = (NodeList) exprFrom.evaluate(source, XPathConstants.NODESET);
        } else if (source instanceof String) {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.newDocument();
            //quirky: create a temporary element, use its nodelist
            Element temp = doc.createElementNS(null, "temp");
            temp.appendChild(doc.createTextNode((String) source));
            nl = temp.getChildNodes();
        } else if (source == null) {
            if (_assignmentsLogger.isDebugEnabled()) {
                _assignmentsLogger.debug("==========SOURCE IS NULL============");
            }
            return;
        }
        
        if (nl.getLength() == 0) {
            throw new RuntimeException("Nothing was selected by the from expression " + from + " on " + sourceExpr);
        }
        for (int i = 0 ; i < nl.getLength(); i++) {
            
            if (!(targetElem instanceof org.w3c.dom.Node)) {
                if (nl.item(i) instanceof Attr) {
                    targetElem = ((Attr) nl.item(i)).getValue();
                } else if (nl.item(i) instanceof Text) {
                    targetElem = ((Text) nl.item(i)).getWholeText();
                } else {
                    DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                    Document doc = builder.newDocument();
                    targetElem  = doc.importNode(nl.item(i), true);
                }
                target = targetElem;
            } else {
                org.w3c.dom.Node n  = ((org.w3c.dom.Node) targetElem).getOwnerDocument().importNode(nl.item(i), true);
                if (n instanceof Attr) {
                    ((Element) targetElem).setAttributeNode((Attr) n);
                } else {
                    ((org.w3c.dom.Node) targetElem).appendChild(n);
                }
            }
        }
        
        if (isInput) {
            ((WorkItem) workItem).setParameter(targetExpr, target);
        } else {
            VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
            resolveContextInstance(VariableScope.VARIABLE_SCOPE, targetExpr);
            variableScopeInstance.setVariable(targetExpr, target);
        }
    }

    public void triggerCompleted(WorkItem workItem) {
        this.workItem = workItem;
        WorkItemNode workItemNode = getWorkItemNode();
        if (workItemNode != null) {
            for (Iterator<DataAssociation> iterator = getWorkItemNode().getOutAssociations().iterator(); iterator.hasNext(); ) {
                DataAssociation association = iterator.next();
                if(association.getAssignments() == null) {
                    VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
                    resolveContextInstance(VariableScope.VARIABLE_SCOPE, association.getTarget());
                    if (variableScopeInstance != null) {
                        Object value = workItem.getResult(association.getSources().get(0));
                        if (value == null) {
                            try {
                                value = MVEL.eval(association.getSources().get(0), new WorkItemResolverFactory(workItem));
                            } catch (Throwable t) {
                                _logger.error(t.getMessage(), t);
                            }
                        }
                        variableScopeInstance.setVariable(association.getTarget(), value);
                    } else {
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Could not find variable scope for variable " + association.getTarget());
                            _logger.debug("when trying to complete Work Item " + workItem.getName());
                            _logger.debug("Continuing without setting variable.");
                        }
                    }
                } else {
                    String source = association.getSources().get(0);
                    String target = association.getTarget();
                    try {
                        for (Iterator<Assignment> it = association.getAssignments().iterator(); it.hasNext(); ) {
                            handleAssignment(it.next(), source, target, false);
                        }
                    } catch (Exception e) {
                        _logger.error(e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                }                
            }
        }
        if (isInversionOfControl()) {
            KnowledgeRuntime kruntime = ((ProcessInstance) getProcessInstance()).getKnowledgeRuntime();
            kruntime.update(kruntime.getFactHandle(this), this);
        } else {
            triggerCompleted();
        }
    }
  
    public void cancel() {
        WorkItem workItem = getWorkItem();
        if (workItem != null &&
                workItem.getState() != WorkItem.COMPLETED && 
                workItem.getState() != WorkItem.ABORTED) {
            try {
                ((WorkItemManager) ((ProcessInstance) getProcessInstance())
                    .getKnowledgeRuntime().getWorkItemManager()).internalAbortWorkItem(workItemId);
            } catch (WorkItemHandlerNotFoundException wihnfe){
                getProcessInstance().setState( ProcessInstance.STATE_ABORTED );
                throw wihnfe;
            }
        }
        super.cancel();
    }
    
    public void addEventListeners() {
        super.addEventListeners();
        addWorkItemListener();
    }
    
    private void addWorkItemListener() {
        getProcessInstance().addEventListener("workItemCompleted", this, false);
        getProcessInstance().addEventListener("workItemAborted", this, false);
    }
    
    public void removeEventListeners() {
        super.removeEventListeners();
        getProcessInstance().removeEventListener("workItemCompleted", this, false);
        getProcessInstance().removeEventListener("workItemAborted", this, false);
    }
    
    public void signalEvent(String type, Object event) {
        if ("workItemCompleted".equals(type)) {
            workItemCompleted((WorkItem) event);
        } else if ("workItemAborted".equals(type)) {
            workItemAborted((WorkItem) event);
        } else {
            super.signalEvent(type, event);
        }
    }

    public String[] getEventTypes() {
        return new String[] { "workItemCompleted" };
    }
    
    public void workItemAborted(WorkItem workItem) {
        if ( workItemId == workItem.getId()
                || ( workItemId == -1 && getWorkItem().getId() == workItem.getId()) ) {
            removeEventListeners();
            triggerCompleted(workItem);
        }
    }

    public void workItemCompleted(WorkItem workItem) {
        if ( workItemId == workItem.getId()
                || ( workItemId == -1 && getWorkItem().getId() == workItem.getId()) ) {
            removeEventListeners();
            triggerCompleted(workItem);
        }
    }
    
    public String getNodeName() {
        Node node = getNode();
        if (node == null) {
            String nodeName =  "[Dynamic]";
            WorkItem workItem = getWorkItem();
            if (workItem != null) {
                nodeName += " " + workItem.getParameter("TaskName");
            }
            return nodeName;
        }
        return super.getNodeName();
    }
    
}
