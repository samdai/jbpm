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

package org.jbpm.bpmn2.xml;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.drools.core.process.core.Work;
import org.drools.core.process.core.datatype.DataType;
import org.drools.core.process.core.impl.WorkImpl;
import org.drools.core.xml.ExtensibleXmlParser;
import org.jbpm.bpmn2.core.ItemDefinition;
import org.jbpm.compiler.xml.ProcessBuildData;
import org.jbpm.workflow.core.Connection;
import org.jbpm.process.core.impl.DataTransformerRegistry;
import org.jbpm.workflow.core.Node;
import org.jbpm.workflow.core.NodeContainer;
import org.jbpm.workflow.core.impl.NodeImpl;
import org.jbpm.workflow.core.impl.ConnectionImpl;
import org.jbpm.workflow.core.impl.ConstraintImpl;
import org.jbpm.workflow.core.node.Assignment;
import org.jbpm.workflow.core.node.CompositeContextNode;
import org.jbpm.workflow.core.node.CompositeNode;
import org.jbpm.workflow.core.node.DataAssociation;
import org.jbpm.workflow.core.node.EndNode;
import org.jbpm.workflow.core.node.ForEachNode;
import org.jbpm.workflow.core.node.Join;
import org.jbpm.workflow.core.node.Split;
import org.jbpm.workflow.core.node.StartNode;
import org.jbpm.workflow.core.node.Transformation;
import org.jbpm.workflow.core.node.WorkItemNode;
import org.kie.api.runtime.process.DataTransformer;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.drools.core.process.core.datatype.impl.type.ObjectDataType;

public class TaskHandler extends AbstractNodeHandler {
    
	private DataTransformerRegistry transformerRegistry = DataTransformerRegistry.get();

    protected Node createNode(Attributes attrs) {
        return new WorkItemNode();
    }
    
	public Class<?> generateNodeFor() {
        return Node.class;
    }

    protected void handleNode(final Node node, final Element element, final String uri, 
            final String localName, final ExtensibleXmlParser parser) throws SAXException {
    	super.handleNode(node, element, uri, localName, parser);
    	WorkItemNode workItemNode = (WorkItemNode) node;
        String name = getTaskName(element);
        Work work = new WorkImpl();
        work.setName(name);
    	workItemNode.setWork(work);
    	org.w3c.dom.Node xmlNode = element.getFirstChild();
        while (xmlNode != null) {
        	String nodeName = xmlNode.getNodeName();
        	if ("ioSpecification".equals(nodeName)) {
        		readIoSpecification(xmlNode, dataInputs, dataOutputs);
        	} else if ("dataInputAssociation".equals(nodeName)) {
        		readDataInputAssociation(xmlNode, workItemNode, dataInputs);
        	} else if ("dataOutputAssociation".equals(nodeName)) {
        		readDataOutputAssociation(xmlNode, workItemNode, dataOutputs);
        	}
    		xmlNode = xmlNode.getNextSibling();
        }
        handleScript(workItemNode, element, "onEntry");
        handleScript(workItemNode, element, "onExit");
        
        String compensation = element.getAttribute("isForCompensation");
        if( compensation != null ) {
            boolean isForCompensation = Boolean.parseBoolean(compensation);
            if( isForCompensation ) { 
                workItemNode.setMetaData("isForCompensation", isForCompensation );
            }
        }  
	}
    
    protected String getTaskName(final Element element) {
        return element.getAttribute("taskName");
    }

    protected void readDataInputAssociation(org.w3c.dom.Node xmlNode, WorkItemNode workItemNode, Map<String, String> dataInputs) {
		// sourceRef
		org.w3c.dom.Node subNode = xmlNode.getFirstChild();
		if ("sourceRef".equals(subNode.getNodeName())) {
    		String source = subNode.getTextContent();    		
    		// targetRef
    		subNode = subNode.getNextSibling();
    		String target = subNode.getTextContent();
    		// transformation
    		Transformation transformation = null;
    		subNode = subNode.getNextSibling();
    		if (subNode != null && "transformation".equals(subNode.getNodeName())) {
    			String lang = subNode.getAttributes().getNamedItem("language").getNodeValue();
    			String expression = subNode.getTextContent();
    			
    			DataTransformer transformer = transformerRegistry.find(lang);
    			if (transformer == null) {
    				throw new IllegalArgumentException("No transformer registered for language " + lang);
    			}    			
    			transformation = new Transformation(lang, expression);
//    			transformation.setCompiledExpression(transformer.compile(expression));
    			
    			subNode = subNode.getNextSibling();
    		}
    		// assignments    	
    		List<Assignment> assignments = new LinkedList<Assignment>();
    		while(subNode != null){
    			org.w3c.dom.Node ssubNode = subNode.getFirstChild();
    			String from = ssubNode.getTextContent();
    			String to = ssubNode.getNextSibling().getTextContent();
    			assignments.add(new Assignment(((Element) xmlNode).getAttribute("language"), from, to));

        		subNode = subNode.getNextSibling();
    		}
    		    		
    		workItemNode.addInAssociation(new DataAssociation(
    				source,
    				dataInputs.get(target), assignments, transformation));
		} else {
			// targetRef
			String to = subNode.getTextContent();
			// assignment
			subNode = subNode.getNextSibling();
			if (subNode != null) {
	    		org.w3c.dom.Node subSubNode = subNode.getFirstChild();
	    		NodeList nl = subSubNode.getChildNodes();
	    		if (nl.getLength() > 1) {
	    		    // not supported ?
	    		    workItemNode.getWork().setParameter(dataInputs.get(to), subSubNode.getTextContent());
	    		    return;
	    		} else if (nl.getLength() == 0) {
	    		    return;
	    		}
	    		Object result = null;
	    		Object from = nl.item(0);
	    		if (from instanceof Text) {
	    		    String text = ((Text) from).getTextContent();
	    		    if (text.startsWith("\"") && text.endsWith("\"")) {
	                    result = text.substring(1, text.length() -1);
	    		    } else {
	    		        result = text;
	    		    }
				} else {
				    result = nl.item(0);
				}
	    		workItemNode.getWork().setParameter(dataInputs.get(to), result);
			}
		}
    }
    
    protected void readDataOutputAssociation(org.w3c.dom.Node xmlNode, WorkItemNode workItemNode, Map<String, String> dataOutputs) {
		// sourceRef
		org.w3c.dom.Node subNode = xmlNode.getFirstChild();
		if ("sourceRef".equals(subNode.getNodeName())) {
		String source = subNode.getTextContent();
		// targetRef
		subNode = subNode.getNextSibling();
		String target = subNode.getTextContent();
		// transformation
		Transformation transformation = null;
		subNode = subNode.getNextSibling();
		if (subNode != null && "transformation".equals(subNode.getNodeName())) {
			String lang = subNode.getAttributes().getNamedItem("language").getNodeValue();
			String expression = subNode.getTextContent();
			DataTransformer transformer = transformerRegistry.find(lang);
			if (transformer == null) {
				throw new IllegalArgumentException("No transformer registered for language " + lang);
			}    			
			transformation = new Transformation(lang, expression, source);
//			transformation.setCompiledExpression(transformer.compile(expression));
			subNode = subNode.getNextSibling();
		}
		// assignments  
		List<Assignment> assignments = new LinkedList<Assignment>();
		while(subNode != null){
			org.w3c.dom.Node ssubNode = subNode.getFirstChild();
			String from = ssubNode.getTextContent();
			String to = ssubNode.getNextSibling().getTextContent();
			assignments.add(new Assignment(((Element) xmlNode).getAttribute("language"), from, to));

    		subNode = subNode.getNextSibling();
		}
		workItemNode.addOutAssociation(new DataAssociation(dataOutputs.get(source), target, assignments, transformation));
		} else {
			// targetRef
			String target = subNode.getTextContent();
			subNode = subNode.getNextSibling();
			List<Assignment> assignments = new LinkedList<Assignment>();
			while(subNode != null){
				org.w3c.dom.Node ssubNode = subNode.getFirstChild();
				String from = ssubNode.getTextContent();
				String to = ssubNode.getNextSibling().getTextContent();
    		    if (from.startsWith("\"") && from.endsWith("\"")) {
                    from = from.substring(1, from.length() -1);
    		    }
				assignments.add(new Assignment(((Element) xmlNode).getAttribute("language"), from, to));

	    		subNode = subNode.getNextSibling();
			}
			workItemNode.addOutAssociation(new DataAssociation(new LinkedList<String>(), target, assignments, null));			
		}
    }

    @Override
    public void writeNode(Node node, StringBuilder xmlDump, int metaDataType) {
        throw new IllegalArgumentException(
            "Writing out should be handled by the WorkItemNodeHandler");
    }
    
    public Object end(final String uri, final String localName,
            final ExtensibleXmlParser parser) throws SAXException {
		final Element element = parser.endElementBuilder();
		Node node = (Node) parser.getCurrent();
		// determine type of event definition, so the correct type of node can be generated
    	handleNode(node, element, uri, localName, parser);
		boolean found = false;
		org.w3c.dom.Node xmlNode = element.getFirstChild();
		int uniqueIdGen = 1;
		while (xmlNode != null) {
			String nodeName = xmlNode.getNodeName();
			if ("multiInstanceLoopCharacteristics".equals(nodeName)) {
				// create new timerNode
				long id = node.getId();
				ForEachNode forEachNode = new ForEachNode(node);
				forEachNode.setId(id);
				forEachNode.setName(node.getName());
				String uniqueId = (String) node.getMetaData().get("UniqueId");
				forEachNode.setMetaData("UniqueId", uniqueId);
				node.setMetaData("UniqueId", uniqueId + ":" + uniqueIdGen++);
				forEachNode.addNode(node);
				forEachNode.setInMapping(((WorkItemNode) node).getInAssociations());
				forEachNode.setOutMapping(((WorkItemNode) node).getOutAssociations());
				node = forEachNode;
				handleForEachNode(node, element, uri, localName, parser);
				found = true;
				break;
			}
			if ("standardLoopCharacteristics".equals(nodeName)) {
				CompositeNode composite = new CompositeContextNode();
				composite.setId(node.getId());
				composite.setName(node.getName());
				composite.setMetaData("UniqueId", node.getMetaData().get("UniqueId"));

				StartNode start = new StartNode();
				composite.addNode(start);

				Join join = new Join();
				join.setType(Join.TYPE_XOR);
				composite.addNode(join);

				Split split = new Split(Split.TYPE_XOR);
				composite.addNode(split);

				node.setId(4);
				composite.addNode(node);

				EndNode end = new EndNode();
				composite.addNode(end);
				end.setTerminate(false);

				new ConnectionImpl(
						composite.getNode(1), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE,
						composite.getNode(2), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE
				);
				new ConnectionImpl(
						composite.getNode(2), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE,
						composite.getNode(3), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE
				);
				Connection c1 = new ConnectionImpl(
						composite.getNode(3), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE,
						composite.getNode(4), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE
				);
				new ConnectionImpl(
						composite.getNode(4), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE,
						composite.getNode(2), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE
				);
				Connection c2 = new ConnectionImpl(
						composite.getNode(3), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE,
						composite.getNode(5), org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE
				);

				composite.setMetaData("hidden", true);
				start.setMetaData("hidden", true);
				join.setMetaData("hidden", true);
				split.setMetaData("hidden", true);
				end.setMetaData("hidden", true);

				String language = "XPath";
				if(((Element) xmlNode.getFirstChild()).getAttribute("language") != null && !"".equals(((Element) xmlNode.getFirstChild()).getAttribute("language"))) {
					language = ((Element) xmlNode.getFirstChild()).getAttribute("language");
				}
				ConstraintImpl cons1 = new ConstraintImpl();
				cons1.setDialect(language);
				cons1.setConstraint(xmlNode.getFirstChild().getTextContent());
				cons1.setType("code");
				cons1.setName("");
				split.setConstraint(c1, cons1);

				ConstraintImpl cons2 = new ConstraintImpl();
				cons2.setDialect(language);
				cons2.setConstraint("");
				cons2.setType("code");
				cons2.setDefault(true);
				cons2.setName("");
				split.setConstraint(c2, cons2);		        

				super.handleNode(node, element, uri, localName, parser);
				node = composite;
				found = true;
				break;
			}

			xmlNode = xmlNode.getNextSibling();
		}
		
		NodeContainer nodeContainer = (NodeContainer) parser.getParent();
		nodeContainer.addNode(node);
		return node;
	}
	protected void handleForEachNode(final Node node, final Element element, final String uri, 
            final String localName, final ExtensibleXmlParser parser) throws SAXException {
    	ForEachNode forEachNode = (ForEachNode) node;
    	org.w3c.dom.Node xmlNode = element.getFirstChild();
    	
        while (xmlNode != null) {
            String nodeName = xmlNode.getNodeName();
            if ("dataInputAssociation".equals(nodeName)) {
                readDataInputAssociation(xmlNode, inputAssociation);
            } else if ("dataOutputAssociation".equals(nodeName)) {
                readDataOutputAssociation(xmlNode, outputAssociation);
            } else if ("multiInstanceLoopCharacteristics".equals(nodeName)) {
            	readMultiInstanceLoopCharacteristics(xmlNode, forEachNode, parser);
            }
            xmlNode = xmlNode.getNextSibling();
        }
    }

	@SuppressWarnings("unchecked")
	protected void readMultiInstanceLoopCharacteristics(org.w3c.dom.Node xmlNode, ForEachNode forEachNode, ExtensibleXmlParser parser) {
	    String isParallel = xmlNode.getAttributes().getNamedItem("isParallel").getNodeValue();
	    if(isParallel != null) {
	    	forEachNode.setParallel(isParallel.equals("true"));
	    }
	    // sourceRef
        org.w3c.dom.Node subNode = xmlNode.getFirstChild();
        while (subNode != null) {
            String nodeName = subNode.getNodeName();
            if ("inputDataItem".equals(nodeName)) {
            	String variableName = ((Element) subNode).getAttribute("id");
            	String itemSubjectRef = ((Element) subNode).getAttribute("itemSubjectRef");
            	DataType dataType = null;
            	Map<String, ItemDefinition> itemDefinitions = (Map<String, ItemDefinition>)
	            	((ProcessBuildData) parser.getData()).getMetaData("ItemDefinitions");
		        if (itemDefinitions != null) {
		        	ItemDefinition itemDefinition = itemDefinitions.get(itemSubjectRef);
		        	if (itemDefinition != null) {
		        		dataType = new ObjectDataType(itemDefinition.getStructureRef());
		        	}
		        }
		        if (dataType == null) {
		        	dataType = new ObjectDataType("java.lang.Object");
		        }
                if (variableName != null && variableName.trim().length() > 0) {
                	forEachNode.setVariable(variableName, dataType);
                }
            } else if ("outputDataItem".equals(nodeName)) {
                String variableName = ((Element) subNode).getAttribute("id");
                String itemSubjectRef = ((Element) subNode).getAttribute("itemSubjectRef");
                DataType dataType = null;
                Map<String, ItemDefinition> itemDefinitions = (Map<String, ItemDefinition>)
                    ((ProcessBuildData) parser.getData()).getMetaData("ItemDefinitions");
                if (itemDefinitions != null) {
                    ItemDefinition itemDefinition = itemDefinitions.get(itemSubjectRef);
                    if (itemDefinition != null) {
                        dataType = new ObjectDataType(itemDefinition.getStructureRef());
                    }
                }
                if (dataType == null) {
                    dataType = new ObjectDataType("java.lang.Object");
                }
                if (variableName != null && variableName.trim().length() > 0) {
                    forEachNode.setOutputVariable(variableName, dataType);
                }
            } else if ("loopDataOutputRef".equals(nodeName)) {
                
                String outputDataRef = ((Element) subNode).getTextContent();
                
                String outputDataName = dataOutputs.get(outputDataRef);
                if (outputDataName != null && outputDataName.trim().length() > 0) {
                    forEachNode.setOutputCollectionExpression(outputDataName);
                }
                
            }
            else if("loopDataInputRef".equals(nodeName)) {
                String inputVariable = subNode.getFirstChild().getTextContent();
                if (inputVariable != null && inputVariable.trim().length() > 0) {
//                	forEachNode.setCollectionExpression(dataInputs.get(inputVariable));
                	forEachNode.setCollectionExpression(inputVariable);
                }
            }
            subNode = subNode.getNextSibling();
        }
    }
}
