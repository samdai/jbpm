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

package org.jbpm.workflow.instance.node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;


import org.jbpm.process.core.ContextContainer;
import org.jbpm.process.core.context.variable.VariableScope;
import org.jbpm.process.instance.ContextInstance;
import org.jbpm.process.instance.context.variable.VariableScopeInstance;
import org.jbpm.workflow.core.node.ForEachNode;
import org.jbpm.workflow.core.node.ForEachNode.ForEachJoinNode;
import org.jbpm.workflow.core.node.ForEachNode.ForEachSplitNode;
import org.jbpm.workflow.instance.NodeInstance;
import org.jbpm.workflow.instance.NodeInstanceContainer;
import org.jbpm.workflow.instance.impl.NodeInstanceImpl;
import org.jbpm.workflow.instance.impl.NodeInstanceResolverFactory;
import org.kie.api.definition.process.Connection;
import org.kie.api.definition.process.Node;
import org.mvel2.MVEL;

import org.w3c.dom.Element;

/**
 * Runtime counterpart of a for each node.
 * 
 * @author <a href="mailto:kris_verlaenen@hotmail.com">Kris Verlaenen</a>
 */
public class ForEachNodeInstance extends CompositeContextNodeInstance {

    private static final long serialVersionUID = 510l;
   
    private static final String TEMP_OUTPUT_VAR = "foreach_output";
    
    public ForEachNode getForEachNode() {
        return (ForEachNode) getNode();
    }

    public NodeInstance getNodeInstance(final Node node) {
        // TODO do this cleaner for split / join of for each?
        if (node instanceof ForEachSplitNode) {
            ForEachSplitNodeInstance nodeInstance = new ForEachSplitNodeInstance();
            nodeInstance.setNodeId(node.getId());
            nodeInstance.setNodeInstanceContainer(this);
            nodeInstance.setProcessInstance(getProcessInstance());
            String uniqueID = (String) node.getMetaData().get("UniqueId");
            assert uniqueID != null : node.getName() + " does not have a unique id.";
            if (uniqueID == null) {
                uniqueID = node.getId()+"";
            }
            int level = this.getLevelForNode(uniqueID);
            nodeInstance.setLevel(level);
            return nodeInstance;
        } else if (node instanceof ForEachJoinNode) {
            ForEachJoinNodeInstance nodeInstance = (ForEachJoinNodeInstance)
                getFirstNodeInstance(node.getId());
            if (nodeInstance == null) {
                nodeInstance = new ForEachJoinNodeInstance();
                nodeInstance.setNodeId(node.getId());
                nodeInstance.setNodeInstanceContainer(this);
                nodeInstance.setProcessInstance(getProcessInstance());
                String uniqueID = (String) node.getMetaData().get("UniqueId");
                assert uniqueID != null : node.getName() + " does not have a unique id.";
                if (uniqueID == null) {
                    uniqueID = node.getId()+"";
                }
                int level = this.getLevelForNode(uniqueID);
                nodeInstance.setLevel(level);
            }
            return nodeInstance;
        }
        return super.getNodeInstance(node);
    }
    
    @Override
    public ContextContainer getContextContainer() {
        return (ContextContainer) getForEachNode().getCompositeNode();
    }
    
    public class ForEachSplitNodeInstance extends NodeInstanceImpl {

        private static final long serialVersionUID = 510l;
        
        public ForEachSplitNode getForEachSplitNode() {
            return (ForEachSplitNode) getNode();
        }

        public void internalTrigger(org.kie.api.runtime.process.NodeInstance fromm, String type) {
            String collectionExpression = getForEachNode().getCollectionExpression();
            Collection<?> collection = evaluateCollectionExpression(collectionExpression);
            ((NodeInstanceContainer) getNodeInstanceContainer()).removeNodeInstance(this);
            if (collection.isEmpty()) {
            	ForEachNodeInstance.this.triggerCompleted(org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE, true);
            } else {
            	List<NodeInstance> nodeInstances = new ArrayList<NodeInstance>();
            	for (Object o: collection) {
            		String variableName = getForEachNode().getVariableName();
            		CompositeNodeInstance nodeInstance = (CompositeNodeInstance)
            		((NodeInstanceContainer) getNodeInstanceContainer()).getNodeInstance(getForEachSplitNode().getTo().getTo());
            		VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
            		nodeInstance.resolveContextInstance(VariableScope.VARIABLE_SCOPE, variableName);
            		variableScopeInstance.setVariable(variableName, o);
            		nodeInstances.add(nodeInstance);
            	}
            	if(getForEachNode().isParallel()) {
            	for (NodeInstance nodeInstance: nodeInstances) {
            		((org.jbpm.workflow.instance.NodeInstance) nodeInstance).trigger(this, getForEachSplitNode().getTo().getToType());
            	}
            	} else {
            		if(!nodeInstances.isEmpty()) {
            			((org.jbpm.workflow.instance.NodeInstance) nodeInstances.get(0)).trigger(this, getForEachSplitNode().getTo().getToType());
            		}
            	}
	            if (!getForEachNode().isWaitForCompletion()) {
	            	ForEachNodeInstance.this.triggerCompleted(org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE, false);
	            }
            }
        }

        private Collection<?> evaluateCollectionExpression(String collectionExpression) {
            // TODO: should evaluate this expression using MVEL
        	Object collection = null;
            VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
                resolveContextInstance(VariableScope.VARIABLE_SCOPE, collectionExpression.substring(0, collectionExpression.indexOf(".")));
            if (variableScopeInstance != null) {
            	collection = ((Element) variableScopeInstance.getVariable(collectionExpression.substring(0, collectionExpression.indexOf(".")))).getAttribute(collectionExpression.substring(collectionExpression.indexOf(".")+1));
            	String c = (String) collection;
            	collection = (c.substring(1, c.length()-1)).split(",");
            } else {
            	try {
            		collection = MVEL.eval(collectionExpression, new NodeInstanceResolverFactory(this));
            	} catch (Throwable t) {
            		throw new IllegalArgumentException(
                        "Could not find collection " + collectionExpression);
            	}
                
            }
            if (collection == null) {
            	return Collections.EMPTY_LIST;
            }
            if (collection instanceof Collection<?>) {
            	return (Collection<?>) collection;
            }
            if (collection.getClass().isArray() ) {
            	List<Object> list = new ArrayList<Object>();
            	for (Object o: (Object[]) collection) {
            		if(!"".equals(o)) {
            			list.add(o);
            		}
            	}
                return list;
            }
            throw new IllegalArgumentException(
        		"Unexpected collection type: " + collection.getClass());
        }
        
    }
    
    public class ForEachJoinNodeInstance extends NodeInstanceImpl {

        private static final long serialVersionUID = 510l;
        
        public ForEachJoinNode getForEachJoinNode() {
            return (ForEachJoinNode) getNode();
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void internalTrigger(org.kie.api.runtime.process.NodeInstance from, String type) {
            
            VariableScopeInstance subprocessVariableScopeInstance = null;
            if (getForEachNode().getOutputVariableName() != null) {
                subprocessVariableScopeInstance = (VariableScopeInstance) getContextInstance(VariableScope.VARIABLE_SCOPE);
                
                Collection<Object> outputCollection = (Collection<Object>) subprocessVariableScopeInstance.getVariable(TEMP_OUTPUT_VAR);
                if (outputCollection == null) {
                    outputCollection = new ArrayList<Object>();
                }
            
                VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
                ((NodeInstanceImpl)from).resolveContextInstance(VariableScope.VARIABLE_SCOPE, getForEachNode().getOutputVariableName());
                Object outputVariable = null;
                if (variableScopeInstance != null) {
                    outputVariable = variableScopeInstance.getVariable(getForEachNode().getOutputVariableName());
                }
                outputCollection.add(outputVariable);
                
                subprocessVariableScopeInstance.setVariable(TEMP_OUTPUT_VAR, outputCollection);
            }
            if (getNodeInstanceContainer().getNodeInstances().size() == 1) {
                String outputCollection = getForEachNode().getOutputCollectionExpression();
                if (outputCollection != null) {
                    VariableScopeInstance variableScopeInstance = (VariableScopeInstance) resolveContextInstance(VariableScope.VARIABLE_SCOPE, outputCollection);
                    Collection<?> outputVariable = (Collection<?>) variableScopeInstance.getVariable(outputCollection);
                    if (outputVariable != null) {
                        outputVariable.addAll((Collection) subprocessVariableScopeInstance.getVariable(TEMP_OUTPUT_VAR));
                    } else {
                        outputVariable = (Collection<Object>) subprocessVariableScopeInstance.getVariable(TEMP_OUTPUT_VAR);
                    }
                    variableScopeInstance.setVariable(outputCollection, outputVariable);
                }
            	((NodeInstanceContainer) getNodeInstanceContainer()).removeNodeInstance(this);
                if (getForEachNode().isWaitForCompletion()) {
                	
                	if (!"true".equals(System.getProperty("jbpm.enable.multi.con"))) {
                		
                		triggerConnection(getForEachJoinNode().getTo());
                	} else {
                	
	                    List<Connection> connections = getForEachJoinNode().getOutgoingConnections(org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE);
	                	for (Connection connection : connections) {
	                	    triggerConnection(connection);
	                	}
                	}
                }
            } else if(!getForEachNode().isParallel()) {
            	org.jbpm.workflow.instance.NodeInstance _instance = null;
            	for( org.kie.api.runtime.process.NodeInstance nodeInstance : getNodeInstanceContainer().getNodeInstances()) {
            		if(nodeInstance != this) {
            			_instance = (org.jbpm.workflow.instance.NodeInstance) nodeInstance;
            			break;
            		}
            	}
                ((NodeInstanceContainer) getNodeInstanceContainer()).removeNodeInstance(this);
            	_instance.trigger(this, org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE);
            } else {
            	((NodeInstanceContainer) getNodeInstanceContainer()).removeNodeInstance(this);
            }
        }
        
    }

    @Override
    public ContextInstance getContextInstance(String contextId) {
        ContextInstance contextInstance = super.getContextInstance(contextId);
        if (contextInstance == null) {
            contextInstance = resolveContextInstance(contextId, TEMP_OUTPUT_VAR);
            setContextInstance(contextId, contextInstance);
        }
        
        return contextInstance;
    }

    @Override
    public int getLevelForNode(String uniqueID) {
        // always 1 for for each
        return 1;
    }  
}
