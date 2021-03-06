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

import java.io.Serializable;

import org.kie.api.runtime.process.EventListener;
import org.kie.api.runtime.process.NodeInstance;
import org.jbpm.process.core.context.variable.VariableScope;
import org.jbpm.process.core.event.EventTransformer;
import org.jbpm.process.instance.context.variable.VariableScopeInstance;
import org.jbpm.workflow.core.node.EventNode;
import org.jbpm.workflow.instance.impl.ExtendedNodeInstanceImpl;

/**
 * Runtime counterpart of an event node.
 * 
 * @author <a href="mailto:kris_verlaenen@hotmail.com">Kris Verlaenen</a>
 */
public class EventNodeInstance extends ExtendedNodeInstanceImpl implements EventNodeInstanceInterface, EventBasedNodeInstanceInterface {

    private static final long serialVersionUID = 510l;
    
    private Object _var=null;
    private EventListener listener = new ExternalEventListener();

    public void signalEvent(String type, Object event) {
    	String variableName = getEventNode().getVariableName();
    	if (variableName != null) {
    		VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
    			resolveContextInstance(VariableScope.VARIABLE_SCOPE, variableName);
    		if (variableScopeInstance == null) {
    			throw new IllegalArgumentException(
					"Could not find variable for event node: " + variableName);
    		}
    		EventTransformer transformer = getEventNode().getEventTransformer();
    		if (transformer != null) {
    			event = transformer.transformEvent(event);
    		}
    		variableScopeInstance.setVariable(variableName, event);
    	}
    	variableName = "__variable__";
    	VariableScopeInstance variableScopeInstance = (VariableScopeInstance)
    			resolveContextInstance(VariableScope.VARIABLE_SCOPE, variableName);
    	if (variableScopeInstance != null) {
        	_var = variableScopeInstance.getVariable(variableName);
    	}
    	
    	// if it is boundary event, trigger() has not been called, so call it here
    	// if it is normal flow, trigger() has been called already, so do not call again, only call trigerCompleted()
    	if(getEventNode().getMetaData("AttachedTo") != null) {
    		trigger(null, org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE);
    	}
    	triggerCompleted();
    }
    
    @Override
    public void internalTrigger(final NodeInstance from, String type) {
        super.internalTrigger(from, type);
    	if (!org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE.equals(type)) {
            throw new IllegalArgumentException(
                "An EventNode only accepts default incoming connections!");
        }    	
    	addEventListeners();
        // Do nothing, event activated
    }
    
    public EventNode getEventNode() {
        return (EventNode) getNode();
    }

    public void triggerCompleted() {   
    	getProcessInstance().removeEventListener(getEventNode().getType(), listener, true);
        ((org.jbpm.workflow.instance.NodeInstanceContainer)getNodeInstanceContainer()).setCurrentLevel(getLevel());
        triggerCompleted(org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE, true);
    }

    public Object getVar() {
        return _var;
    }

    
    @Override
	public void cancel() {
    	getProcessInstance().removeEventListener(getEventNode().getType(), listener, true);
		super.cancel();
	}

	private class ExternalEventListener implements EventListener, Serializable {
		private static final long serialVersionUID = 5L;
		public String[] getEventTypes() {
			return null;
		}
		public void signalEvent(String type,
				Object event) {
		}		
	}
    
	@Override
	public void addEventListeners() {
		getProcessInstance().addEventListener(getEventNode().getType(), listener, true);
	}

	@Override
	public void removeEventListeners() {
		
		
	}
}
