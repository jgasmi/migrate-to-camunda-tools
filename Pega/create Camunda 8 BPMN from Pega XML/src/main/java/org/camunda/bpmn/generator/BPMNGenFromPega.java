package org.camunda.bpmn.generator;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.*;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.bpmndi.BpmnDiagram;
import io.camunda.zeebe.model.bpmn.instance.bpmndi.BpmnPlane;
import static io.camunda.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN20_NS;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;

public class BPMNGenFromPega {

    /**
     * Refactored method that actually performs the XML -> BPMN conversion.
     */
    public static void convertFile(String inputXmlPath, String outputBpmnPath) throws Exception {
        // -- The contents below are essentially what you have in main(...) now,
        //    just replacing "args[0]" and "args[1]" with parameters.

        File file = new File(inputXmlPath);

        // Create hash map to map ids in old file node objects with ids in new file
        HashMap<String, Object> idMap = new HashMap<>();

        // Create hash of flow nodes for drawing of sequence flows later
        HashMap<String, Object> flowNodesMap = new HashMap<>();

        // Create hash map of Pega elements to search for
        HashMap<String, Object> pegaElementsMap = new HashMap<>();

        // Fill pegaElementsMap with Pega-to-BPMN element mappings ...
        PegaToBPMNElement bpmnElement = new PegaToBPMNElement(StartEvent.class, 36d, 36d);
        pegaElementsMap.put("Data-MO-Event-Start", bpmnElement);
        bpmnElement = new PegaToBPMNElement(UserTask.class, 80d, 100d);
        pegaElementsMap.put("Data-MO-Activity-Assignment", bpmnElement);
        pegaElementsMap.put("Data-MO-Activity-Assignment-WorkAction", bpmnElement);
        bpmnElement = new PegaToBPMNElement(IntermediateCatchEvent.class, 36d, 36d);
        pegaElementsMap.put("Data-MO-Activity-Assignment-Wait", bpmnElement);
        bpmnElement = new PegaToBPMNElement(SubProcess.class, 80d, 100d);
        pegaElementsMap.put("Data-MO-Activity-SubProcess", bpmnElement);
        pegaElementsMap.put("Data-MO-Activity-SubProcess-Spinoff", bpmnElement);
        pegaElementsMap.put("Data-MO-Activity-SubProcess-SplitForEach", bpmnElement);
        bpmnElement = new PegaToBPMNElement(SendTask.class, 80d, 100d);
        pegaElementsMap.put("Data-MO-Activity-Notify", bpmnElement);
        bpmnElement = new PegaToBPMNElement(ServiceTask.class, 80d, 100d);
        pegaElementsMap.put("Data-MO-Activity-Utility", bpmnElement);
        pegaElementsMap.put("Data-MO-Activity-Integrator", bpmnElement);
        pegaElementsMap.put("Data-MO-Activity-Router", bpmnElement);
        bpmnElement = new PegaToBPMNElement(EndEvent.class, 36d, 36d);
        pegaElementsMap.put("Data-MO-Event-End", bpmnElement);
        pegaElementsMap.put("Data-MO-Event-Exception", bpmnElement);
        bpmnElement = new PegaToBPMNElement(ExclusiveGateway.class, 50d, 50d);
        pegaElementsMap.put("Data-MO-Gateway-Decision", bpmnElement);
        pegaElementsMap.put("Data-MO-Gateway-DataXOR", bpmnElement);
        //bpmnElement = new PegaToBPMNElement(SequenceFlow.class, 0d,0d);
        //pegaElementsMap.put("Data-MO-Connector-Transition", bpmnElement);

        // Read document in preparation for Xpath searches
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(file);
        doc.getDocumentElement().normalize();

        // Create BPMN model using Camunda Model APIs
        BpmnModelInstance modelInstance = Bpmn.createEmptyModel();
        Definitions definitions = modelInstance.newInstance(Definitions.class);
        definitions.setTargetNamespace(BPMN20_NS);
        definitions.setAttributeValueNs("http://camunda.org/schema/modeler/1.0",
                "modeler:executionPlatform","Camunda Cloud");
        modelInstance.setDefinitions(definitions);

        Process process = modelInstance.newInstance(Process.class);
        process.setExecutable(true); // Want to make sure it is executable by default in Modeler
        definitions.addChildElement(process);


        // For the diagram, a diagram and a plane element needs to be created. The plane is set in a diagram object and the diagram is added as a child element
        BpmnDiagram bpmnDiagram = modelInstance.newInstance(BpmnDiagram.class);
        BpmnPlane plane = modelInstance.newInstance(BpmnPlane.class);
        plane.setBpmnElement(process);
        bpmnDiagram.setBpmnPlane(plane);
        definitions.addChildElement(bpmnDiagram);

        //NodeList pyShapeTypeList = doc.getElementsByTagName("pyShapeType");
        NodeList pyShapeTypeList = doc.getElementsByTagName("pyFromClass");

        XPathExpression searchRequest = null;
        XPath xpath = XPathFactory.newInstance().newXPath();

        Iterator iter = pegaElementsMap.keySet().iterator();
        while(iter.hasNext()) {
            String key = (String) iter.next();
            bpmnElement = (PegaToBPMNElement) pegaElementsMap.get(key);
            searchRequest = xpath.compile("//pyShapeType[text() = '"+key+"']");
            pyShapeTypeList = (NodeList) searchRequest.evaluate(doc, XPathConstants.NODESET);
            for (int i = 0; i < pyShapeTypeList.getLength(); i++) {
                Node parentNode = pyShapeTypeList.item(i).getParentNode();
                searchRequest = xpath.compile("pyCoordX");
                NodeList xCoordList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
                searchRequest = xpath.compile("pyCoordY");
                NodeList yCoordList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
                Double newX = (Double.valueOf(xCoordList.item(0).getTextContent()) + 5) * 120;
                Double newY = (Double.valueOf(yCoordList.item(0).getTextContent()) + 5) * 120;

                searchRequest = xpath.compile("pyMOName");
                NodeList nameList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);

                BpmnModelElementInstance element = (BpmnModelElementInstance) modelInstance.newInstance(bpmnElement.getType());
                if(nameList.getLength() > 0) {
                    element.setAttributeValue("name", nameList.item(0).getTextContent());
                }
                // Handle Wait Shape (Timer Intermediate Catch Event)
                if (key.equals("Data-MO-Activity-Assignment-Wait")) {
                    IntermediateCatchEvent timerEvent = (IntermediateCatchEvent) element;

                    // Add TimerEventDefinition
                    TimerEventDefinition timerDef = modelInstance.newInstance(TimerEventDefinition.class);
                    timerEvent.getEventDefinitions().add(timerDef);

                    // Parse Pega's timer properties (pyMinutes, pyTimerType)
                    NodeList timerMinutesList = (NodeList) xpath.compile("pyMinutes").evaluate(parentNode, XPathConstants.NODESET);
                    if (timerMinutesList.getLength() > 0) {
                        String minutes = timerMinutesList.item(0).getTextContent();
                        TimeDuration timeDuration = modelInstance.newInstance(TimeDuration.class);
                        timeDuration.setTextContent("PT" + minutes + "M"); // ISO 8601 format
                        timerDef.setTimeDuration(timeDuration);
                    }
                }
                process.addChildElement(element);
                plane = DrawShape.drawShape(plane, modelInstance, element, newX, newY, bpmnElement.getHeight(), bpmnElement.getWidth(), true, false);

                FlowNodeInfo fni = new FlowNodeInfo(element.getAttributeValue("id"), Double.valueOf(xCoordList.item(0).getTextContent()),  Double.valueOf(yCoordList.item(0).getTextContent()), newX, newY, bpmnElement.getType().toString(), bpmnElement.getHeight(), bpmnElement.getWidth());
                searchRequest = xpath.compile("pyMOId");
                NodeList idList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
                flowNodesMap.put(idList.item(0).getTextContent(), fni);
            }
        }

        // Find end events
        searchRequest = xpath.compile("//pyEndingActivities/rowdata");
        NodeList endingActivitiesList = (NodeList) searchRequest.evaluate(doc, XPathConstants.NODESET);
        for(int i=0; i < endingActivitiesList.getLength(); i++) {
            String endElementId = endingActivitiesList.item(i).getTextContent();

            // Find the Pega element by pyMOId
            NodeList endingActivityList = (NodeList) xpath.compile(
                    "//pyMOId[text() = '" + endElementId + "']"
            ).evaluate(doc, XPathConstants.NODESET);
            if (endingActivityList.getLength() == 0) continue;

            Node parentNode = endingActivityList.item(0).getParentNode();

            // Check if the element is truly an EndEvent (Data-MO-Event-End)
            NodeList pxObjClassList = (NodeList) xpath.compile("pxObjClass").evaluate(parentNode, XPathConstants.NODESET);
            if (pxObjClassList.getLength() == 0 ||
                    !pxObjClassList.item(0).getTextContent().equals("Data-MO-Event-End")) {
                continue; // Skip non-EndEvent elements
            }
            Node endElement = endingActivitiesList.item(i);
            searchRequest = xpath.compile("//pyMOId[text() = '"+endElement.getTextContent()+"']");
            //NodeList endingActivityList = (NodeList) searchRequest.evaluate(doc, XPathConstants.NODESET);
            //Node parentNode = endingActivityList.item(0).getParentNode();
            searchRequest = xpath.compile("pyCoordX");
            NodeList xCoordList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
            searchRequest = xpath.compile("pyCoordY");
            NodeList yCoordList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
            Double newX = (Double.valueOf(xCoordList.item(0).getTextContent()) + 5) * 120;
            Double newY = (Double.valueOf(yCoordList.item(0).getTextContent()) + 5) * 120;
            BpmnModelElementInstance element = (BpmnModelElementInstance) modelInstance.newInstance(EndEvent.class);

            searchRequest = xpath.compile("pyMOName");
            NodeList nameList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
            if(nameList.getLength() > 0) {
                element.setAttributeValue("name", nameList.item(0).getTextContent());
            }
            process.addChildElement(element);
            plane = DrawShape.drawShape(plane, modelInstance, element, newX, newY, 36d, 36d, true, false);

            FlowNodeInfo fni = new FlowNodeInfo(element.getAttributeValue("id"),  Double.valueOf(xCoordList.item(0).getTextContent()),  Double.valueOf(yCoordList.item(0).getTextContent()), newX, newY, EndEvent.class.toString(), 36d, 36d);
            searchRequest = xpath.compile("pyMOId");
            NodeList idList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
            flowNodesMap.put(idList.item(0).getTextContent(), fni);

        }

        // Now draw the sequence flows
        searchRequest = xpath.compile("//pxObjClass[text() = 'Data-MO-Connector-Transition']");
        NodeList sequenceList = (NodeList) searchRequest.evaluate(doc, XPathConstants.NODESET);
        for(int i=0; i < sequenceList.getLength(); i++) {
            Node sequenceElement = sequenceList.item(i);
            Node parentNode = sequenceElement.getParentNode();
            searchRequest = xpath.compile("pyFrom");
            NodeList fromList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
            String fromId = fromList.item(0).getTextContent();
            searchRequest = xpath.compile("pyTo");
            NodeList toList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
            String toId = toList.item(0).getTextContent();
            FlowNodeInfo fromFNI = (FlowNodeInfo) flowNodesMap.get(fromId);
            FlowNodeInfo toFNI = (FlowNodeInfo) flowNodesMap.get(toId);
            if(toFNI != null && fromFNI !=null) {
                SequenceFlow sf = modelInstance.newInstance(SequenceFlow.class);
                process.addChildElement(sf);


                // Get sequence flow name
                searchRequest = xpath.compile("pyMOName");
                NodeList nameList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);
                if (nameList.getLength() > 0) {
                    sf.setAttributeValue("name", nameList.item(0).getTextContent());
                }

                // Get sequence flow waypoint list - currently not used
                searchRequest = xpath.compile("pyConnectorPoints");
                NodeList connectorPointList = (NodeList) searchRequest.evaluate(parentNode, XPathConstants.NODESET);


                String targetId = toFNI.getNewId();
                String sourceId = fromFNI.getNewId();

                FlowNode targetFlowNode = modelInstance.getModelElementById(targetId);
                FlowNode sourceFlowNode = modelInstance.getModelElementById(sourceId);

                sf.setSource(sourceFlowNode);
                sf.setTarget(targetFlowNode);

                plane = DrawFlow.drawFlow(plane, modelInstance, sf, fromFNI, toFNI, connectorPointList, 0d, 0d);
            }
        }

        Bpmn.validateModel(modelInstance);

        File outputFile = new File(outputBpmnPath);
        Bpmn.writeModelToFile(outputFile, modelInstance);

        System.out.println("File " + inputXmlPath + " converted to BPMN: " + outputBpmnPath);
    }


    /**
     * Original main method, now just a thin wrapper that calls `convertFile()`.
     */
    public static void main(String[] args) throws Exception {
        // For backward compatibility with your original approach:
        if (args.length < 2) {
            System.out.println("Usage: java BPMNGenFromPega <input.xml> <output.bpmn>");
            return;
        }
        convertFile(args[0], args[1]);
    }

}
