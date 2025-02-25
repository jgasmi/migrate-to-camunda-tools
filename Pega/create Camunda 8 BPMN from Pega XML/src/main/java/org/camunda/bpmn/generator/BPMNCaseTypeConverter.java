package org.camunda.bpmn.generator;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.*;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.bpmndi.BpmnDiagram;
import io.camunda.zeebe.model.bpmn.instance.bpmndi.BpmnPlane;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.File;
import java.util.*;

public class BPMNCaseTypeConverter {

    // Caches
    private static final Map<String, SubProcess> subProcessCache = new HashMap<>();
    private static final Map<String, String> flowNameToIdMap = new HashMap<>();

    private static DocumentBuilder dBuilder;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: BPMNCaseTypeConverter <input.xml> <output.bpmn>");
            return;
        }
        File caseTypeFile = new File(args[0]);
        File outputFile  = new File(args[1]);

        dBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document caseTypeDoc = dBuilder.parse(caseTypeFile);
        caseTypeDoc.getDocumentElement().normalize();

        // Create empty BPMN
        BpmnModelInstance model = Bpmn.createEmptyModel();
        Definitions defs = model.newInstance(Definitions.class);
        defs.setTargetNamespace("http://bpmn.io/schema/bpmn");
        // Camunda Modeler extension
        defs.setAttributeValueNs("http://camunda.org/schema/modeler/1.0",
                "modeler:executionPlatform", "Camunda Cloud");
        model.setDefinitions(defs);

        // Create top-level process
        Process process = model.newInstance(Process.class);
        process.setId("MainProcess");
        process.setExecutable(true);
        defs.addChildElement(process);

        // Create diagram + plane
        BpmnDiagram diagram = model.newInstance(BpmnDiagram.class);
        BpmnPlane plane    = model.newInstance(BpmnPlane.class);
        plane.setBpmnElement(process);
        diagram.setBpmnPlane(plane);
        defs.addChildElement(diagram);

        // For storing shape positions
        Map<String, FlowNodeInfo> nodeMap = new HashMap<>();

        // parse the stages in caseTypeDoc
        XPath xpath = XPathFactory.newInstance().newXPath();
        processStages(model, caseTypeDoc, xpath, process, plane,
                caseTypeFile, nodeMap);

        // Validation + output
        Bpmn.validateModel(model);
        Bpmn.writeModelToFile(outputFile, model);
        System.out.println("BPMN file created at: " + outputFile.getAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // 1) Process all stages => each stage => SubProcess in main process
    // -------------------------------------------------------------------------
    private static void processStages(BpmnModelInstance model,
                                      Document doc,
                                      XPath xpath,
                                      Process process,
                                      BpmnPlane plane,
                                      File caseTypeFile,
                                      Map<String, FlowNodeInfo> nodeMap) throws Exception {

        // pyStages & pyAlternateStages
        processStageGroup(model, doc, xpath, "//pyStages/rowdata",
                process, plane, caseTypeFile, nodeMap);
        processStageGroup(model, doc, xpath, "//pyAlternateStages/rowdata",
                process, plane, caseTypeFile, nodeMap);
    }

    private static void processStageGroup(BpmnModelInstance model,
                                          Document doc,
                                          XPath xpath,
                                          String expr,
                                          Process parentProcess,
                                          BpmnPlane plane,
                                          File caseTypeFile,
                                          Map<String, FlowNodeInfo> nodeMap) throws Exception {

        NodeList stageNodes = (NodeList) xpath.compile(expr).evaluate(doc, XPathConstants.NODESET);
        FlowNode lastStage = null; // Track previous stage for proper connections

        for (int i = 0; i < stageNodes.getLength(); i++) {
            Element stageEl = (Element) stageNodes.item(i);
            String stageName = getText(stageEl, "pyStageName");
            if (stageName == null) stageName = "Stage_" + i;

            // ✅ Create subprocess for the stage
            SubProcess stageSubProcess = model.newInstance(SubProcess.class);
            stageSubProcess.setId("StageSub_" + UUID.randomUUID().toString().replace("-", ""));
            stageSubProcess.setName(stageName);
            parentProcess.addChildElement(stageSubProcess);

            // ✅ Ensure Task Size is Used (80x100 instead of large size)
            double posX = 100 + (i * 200.0);
            double posY = 100;
            DrawShape.drawShape(plane, model, stageSubProcess, posX, posY, 80, 100, true, false);
            System.out.println("📌 Drawing Stage: " + stageName);

            // ✅ Store Stage in `nodeMap`
            nodeMap.put(stageSubProcess.getId(), new FlowNodeInfo(
                    stageSubProcess.getId(), posX, posY, posX, posY, "SubProcess", 80d, 100d));

            boolean hasParallelProcesses = detectParallelProcesses(stageEl);

            if (hasParallelProcesses) {
                processParallelProcesses(model, stageSubProcess, stageEl, plane, caseTypeFile, nodeMap);
            } else {
                processSequentialProcesses(model, stageSubProcess, stageEl, plane, caseTypeFile, nodeMap);
            }

            // ✅ Ensure Stages are Connected Correctly
            if (lastStage != null) {
                SequenceFlow stageFlow = model.newInstance(SequenceFlow.class);
                stageFlow.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
                stageFlow.setSource(lastStage);
                stageFlow.setTarget(stageSubProcess);
                parentProcess.addChildElement(stageFlow);

                // ✅ Draw Flow Between Stages
                FlowNodeInfo sourceInfo = nodeMap.get(lastStage.getId());
                FlowNodeInfo targetInfo = nodeMap.get(stageSubProcess.getId());

                if (sourceInfo != null && targetInfo != null) {
                    DrawFlow.drawFlow(plane, model, stageFlow, sourceInfo, targetInfo, null, 0d, 0d);
                    System.out.println("✅ Connected Stage: " + lastStage.getId() + " → " + stageSubProcess.getId());
                } else {
                    System.out.println("🚨 Warning: Missing source or target for stage connection.");
                }
            }

            lastStage = stageSubProcess;
        }
    }

    private static boolean detectParallelProcesses(Element stageEl) {
        NodeList processList = stageEl.getElementsByTagName("pyProcesses");
        for (int j = 0; j < processList.getLength(); j++) {
            Element flowEl = (Element) processList.item(j);
            NodeList children = flowEl.getChildNodes();
            int rowDataCount = 0;

            for (int k = 0; k < children.getLength(); k++) {
                if (children.item(k) instanceof Element) {
                    Element childEl = (Element) children.item(k);
                    if (childEl.getTagName().equals("rowdata")) {
                        rowDataCount++;
                    }
                }
            }

            if (rowDataCount > 1) {
                return true; // ✅ Parallel processes detected
            }
        }
        return false; // ❌ Only sequential processes
    }

    private static void processSequentialProcesses(BpmnModelInstance model,
                                                   SubProcess parentSubProcess,
                                                   Element stageEl,
                                                   BpmnPlane plane,
                                                   File caseTypeFile,
                                                   Map<String, FlowNodeInfo> nodeMap) throws Exception {

        NodeList processList = stageEl.getElementsByTagName("pyProcesses");
        FlowNode lastNode = null;

        for (int j = 0; j < processList.getLength(); j++) {
            Element flowEl = (Element) processList.item(j);
            String flowName = getText(flowEl, "pyFlowName");

            if (flowName == null || flowName.isEmpty()) {
                System.out.println("Skipping empty flow name in stage " + parentSubProcess.getName());
                continue;
            }

            // ✅ Find the flow file
            File flowFile = findFlowFile(flowName, caseTypeFile);

            // ✅ Instead of creating a new subprocess, we **directly populate elements** into `parentSubProcess`
            double posX = 150 + (j * 200);
            double posY = 150;
            populateSubProcess(model, parentSubProcess, flowFile, posX, posY, plane, nodeMap, caseTypeFile);

            // ✅ Find the first element (task/gateway/etc.) from the parsed process
            FlowNode firstNode = parentSubProcess.getChildElementsByType(FlowNode.class)
                    .stream()
                    .filter(n -> !(n instanceof StartEvent))
                    .findFirst()
                    .orElse(null);

            // ✅ Ensure process flows are connected in sequence
            if (lastNode != null && firstNode != null) {
                SequenceFlow flow = model.newInstance(SequenceFlow.class);
                flow.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
                flow.setSource(lastNode);
                flow.setTarget(firstNode);
                parentSubProcess.addChildElement(flow);

                // ✅ Draw the flow
                FlowNodeInfo sourceInfo = nodeMap.get(lastNode.getId());
                FlowNodeInfo targetInfo = nodeMap.get(firstNode.getId());

                if (sourceInfo != null && targetInfo != null) {
                    DrawFlow.drawFlow(plane, model, flow, sourceInfo, targetInfo, null, 0d, 0d);
                    System.out.println("✅ Connected Sequential Process: " + lastNode.getId() + " → " + firstNode.getId());
                }
            }

            lastNode = firstNode; // ✅ Update lastNode to track sequence
        }
    }


    private static void processParallelProcesses(BpmnModelInstance model,
                                                 SubProcess parentSubProcess,
                                                 Element stageEl,
                                                 BpmnPlane plane,
                                                 File caseTypeFile,
                                                 Map<String, FlowNodeInfo> nodeMap) throws Exception {
        NodeList pyProcessesNode = stageEl.getElementsByTagName("pyProcesses");
        if (pyProcessesNode.getLength() == 0) return;

        Element pyProcessesEl = (Element) pyProcessesNode.item(0);
        NodeList children = pyProcessesEl.getChildNodes();
        List<Element> processList = new ArrayList<>();

        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                Element childEl = (Element) children.item(i);
                if (childEl.getTagName().equals("rowdata")) {
                    processList.add(childEl);
                }
            }
        }
        if (processList.isEmpty()) return;

        System.out.println("Processing parallel processes for stage: " + stageEl.getAttribute("pyStageName"));

        // 1️⃣ Create & Draw Start Event
        StartEvent startEvent = model.newInstance(StartEvent.class);
        startEvent.setId("Start_" + UUID.randomUUID().toString().replace("-", ""));
        startEvent.setName("Start");
        parentSubProcess.addChildElement(startEvent);
        DrawShape.drawShape(plane, model, startEvent, 100, 100, 36, 36, true, false);
        nodeMap.put(startEvent.getId(), new FlowNodeInfo(startEvent.getId(), 100d, 100d, 100d, 100d, "StartEvent", 36d, 36d));

        // 2️⃣ Create & Draw Parallel Gateway (Split)
        ParallelGateway parallelGateway = model.newInstance(ParallelGateway.class);
        parallelGateway.setId("ParallelGateway_" + UUID.randomUUID().toString().replace("-", ""));
        parentSubProcess.addChildElement(parallelGateway);
        DrawShape.drawShape(plane, model, parallelGateway, 200, 100, 50, 50, true, false);
        nodeMap.put(parallelGateway.getId(), new FlowNodeInfo(parallelGateway.getId(), 200d, 100d, 200d, 100d, "ParallelGateway", 50d, 50d));

        // Connect Start → Parallel Gateway
        SequenceFlow sf1 = model.newInstance(SequenceFlow.class);
        sf1.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
        sf1.setSource(startEvent);
        sf1.setTarget(parallelGateway);
        parentSubProcess.addChildElement(sf1);
        DrawFlow.drawFlow(plane, model, sf1, nodeMap.get(startEvent.getId()), nodeMap.get(parallelGateway.getId()), null, 0d, 0d);

        // 3️⃣ Create & Draw Inclusive Gateway (Merge)
        InclusiveGateway inclusiveGateway = model.newInstance(InclusiveGateway.class);
        inclusiveGateway.setId("InclusiveGateway_" + UUID.randomUUID().toString().replace("-", ""));
        parentSubProcess.addChildElement(inclusiveGateway);
        DrawShape.drawShape(plane, model, inclusiveGateway, 600, 100, 50, 50, true, false);
        nodeMap.put(inclusiveGateway.getId(), new FlowNodeInfo(inclusiveGateway.getId(), 600d, 100d, 600d, 100d, "InclusiveGateway", 50d, 50d));

        int processCount = 0;
        for (Element processEl : processList) {
            String flowName = getText(processEl, "pyFlowName");
            processCount++;

            // ✅ Create & Draw Parallel SubProcess
            SubProcess parallelSubProcess = model.newInstance(SubProcess.class);
            parallelSubProcess.setId("ParallelSub_" + UUID.randomUUID().toString().replace("-", ""));
            parallelSubProcess.setName(flowName);
            parentSubProcess.addChildElement(parallelSubProcess);
            DrawShape.drawShape(plane, model, parallelSubProcess, 300 * processCount, 100, 80, 100, true, false);

            nodeMap.put(parallelSubProcess.getId(), new FlowNodeInfo(parallelSubProcess.getId(), 300d * processCount, 100d, 300d * processCount, 100d, "SubProcess", 80d, 100d));

            // Populate nested content
            File flowFile = findFlowFile(flowName, caseTypeFile);
            populateSubProcess(model, parallelSubProcess, flowFile, 300 * processCount, 100, plane, nodeMap, caseTypeFile);

            // Connect Parallel Gateway → Parallel SubProcess
            SequenceFlow sf2 = model.newInstance(SequenceFlow.class);
            sf2.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
            sf2.setSource(parallelGateway);
            sf2.setTarget(parallelSubProcess);
            parentSubProcess.addChildElement(sf2);
            DrawFlow.drawFlow(plane, model, sf2, nodeMap.get(parallelGateway.getId()), nodeMap.get(parallelSubProcess.getId()), null, 0d, 0d);

            // Connect Parallel SubProcess → Inclusive Gateway
            SequenceFlow sf3 = model.newInstance(SequenceFlow.class);
            sf3.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
            sf3.setSource(parallelSubProcess);
            sf3.setTarget(inclusiveGateway);
            parentSubProcess.addChildElement(sf3);
            DrawFlow.drawFlow(plane, model, sf3, nodeMap.get(parallelSubProcess.getId()), nodeMap.get(inclusiveGateway.getId()), null, 0d, 0d);
        }

        // 4️⃣ Create & Draw End Event
        EndEvent endEvent = model.newInstance(EndEvent.class);
        endEvent.setId("End_" + UUID.randomUUID().toString().replace("-", ""));
        endEvent.setName("End");
        parentSubProcess.addChildElement(endEvent);
        DrawShape.drawShape(plane, model, endEvent, 800, 100, 36, 36, true, false);
        nodeMap.put(endEvent.getId(), new FlowNodeInfo(endEvent.getId(), 800d, 100d, 800d, 100d, "EndEvent", 36d, 36d));

        // Connect Inclusive Gateway → End Event
        SequenceFlow sf4 = model.newInstance(SequenceFlow.class);
        sf4.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
        sf4.setSource(inclusiveGateway);
        sf4.setTarget(endEvent);
        parentSubProcess.addChildElement(sf4);
        DrawFlow.drawFlow(plane, model, sf4, nodeMap.get(inclusiveGateway.getId()), nodeMap.get(endEvent.getId()), null, 0d, 0d);

        System.out.println("✅ Parallel processes successfully processed!");
    }


    // -------------------------------------------------------------------------
    // 2) Populate SubProcess from one flowFile
    // -------------------------------------------------------------------------
    private static void populateSubProcess(BpmnModelInstance model,
                                           SubProcess subProcess,
                                           File flowFile,
                                           double baseX, double baseY,
                                           BpmnPlane plane,
                                           Map<String, FlowNodeInfo> nodeMap,
                                           File caseTypeFile) throws Exception {

        Document flowDoc = dBuilder.parse(flowFile);
        flowDoc.getDocumentElement().normalize();
        XPath xpath = XPathFactory.newInstance().newXPath();

        // 1️⃣ Parse all shapes
        parseAllShapes(model, subProcess, flowDoc, xpath, baseX, baseY, plane, nodeMap, caseTypeFile);

        // 2️⃣ Process sequence flows, ensuring nodeMap has all nodes
        processSequenceFlows(model, subProcess, flowDoc, xpath, nodeMap, plane);

        // 3️⃣ Process use case links
        processUseCaseLinks(model, subProcess, flowDoc, xpath, nodeMap, plane);

        // 4️⃣ Ensure there is a StartEvent, otherwise create one
        boolean hasStart = !subProcess.getChildElementsByType(StartEvent.class).isEmpty();
        if (!hasStart) {
            StartEvent startEv = model.newInstance(StartEvent.class);
            String stId = "ID_Start_" + UUID.randomUUID().toString().replace("-", "");
            startEv.setId(stId);
            startEv.setName("Start");
            subProcess.addChildElement(startEv);

            FlowNode firstNode = subProcess.getChildElementsByType(FlowNode.class)
                    .stream()
                    .filter(n -> !(n instanceof StartEvent))
                    .findFirst()
                    .orElse(null);

            if (firstNode != null) {
                SequenceFlow sf = model.newInstance(SequenceFlow.class);
                sf.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
                sf.setSource(startEv);
                sf.setTarget(firstNode);
                subProcess.addChildElement(sf);

                // ✅ Store start event in nodeMap for proper linking
                nodeMap.put(stId, new FlowNodeInfo(stId, baseX, baseY, baseX, baseY, "StartEvent", 36d, 36d));
            }
        }
    }



    // -------------------------------------------------------------------------
    // 3) parseAllShapes: single pass over pyShapes/rowdata
    // -------------------------------------------------------------------------
    private static void parseAllShapes(BpmnModelInstance model,
                                       SubProcess parentSub,
                                       Document doc,
                                       XPath xpath,
                                       double baseX, double baseY,
                                       BpmnPlane plane,
                                       Map<String, FlowNodeInfo> nodeMap,
                                       File caseTypeFile) throws Exception {

        // find all shapes in pyModelProcess/pyShapes
        NodeList shapeList = (NodeList) xpath.compile("//pyModelProcess/pyShapes/rowdata")
                .evaluate(doc, XPathConstants.NODESET);

        for (int i = 0; i < shapeList.getLength(); i++) {
            Element shapeEl = (Element) shapeList.item(i);

            // read pxObjClass, coords, MOId
            String pxObjClass = getText(shapeEl, "pxObjClass");
            if (pxObjClass == null) pxObjClass = "";
            String moId        = getText(shapeEl, "pyMOId");
            String moName      = getText(shapeEl, "pyMOName");

            String xVal = getText(shapeEl, "pyCoordX");
            String yVal = getText(shapeEl, "pyCoordY");
            if (xVal==null || yVal==null) continue; // skip if missing coords
            double origX = Double.parseDouble(xVal);
            double origY = Double.parseDouble(yVal);

            double calcX = baseX + (origX+5)*120.0;
            double calcY = baseY + (origY+5)*120.0;

            // guess BPMN shape
            PegaToBPMNElement mapping = guessBpmnShape(pxObjClass);

            // create the shape
            if (mapping.getType().equals(SubProcess.class)) {
                // create nested SubProcess
                SubProcess sub = model.newInstance(SubProcess.class);
                String subId = "N_" + UUID.randomUUID().toString().replace("-", "");
                sub.setId(subId);
                sub.setName(moName!=null ? moName : pxObjClass);
                sub.setTriggeredByEvent(false);

                parentSub.addChildElement(sub);

                FlowNodeInfo info = new FlowNodeInfo(
                        subId, origX, origY, calcX, calcY,
                        "SubProcess", mapping.getHeight(), mapping.getWidth()
                );
                if (moId!=null) nodeMap.put(moId, info);

                DrawShape.drawShape(plane, model, sub,
                        calcX, calcY, mapping.getHeight(), mapping.getWidth(),
                        true,false);

                // handle nested flow if any
                String flowName = resolveFlowName(shapeEl, doc, xpath);
                if (flowName != null && !flowName.isEmpty()) {
                    flowNameToIdMap.put(flowName, subId);
                    handleNestedSubProcess(model, sub,
                            flowName, caseTypeFile, calcX,calcY,
                            nodeMap, plane);
                }
            }
            else {
                // normal shape
                FlowNode bpmnEl = (FlowNode) model.newInstance(mapping.getType());
                String elId = "N_" + UUID.randomUUID().toString().replace("-", "");
                bpmnEl.setId(elId);
                if (moName!=null) bpmnEl.setName(moName);

                if (mapping.getType().equals(ExclusiveGateway.class)) {
                    System.out.println("Adding gateway: " + moName + " with ID: " + elId);

                    System.out.println("📍 Gateway Position: " + elId + " at (" + calcX + ", " + calcY + ")");

                    // Ensure gateways are stored in nodeMap with both keys
                    FlowNodeInfo info = new FlowNodeInfo(
                            elId, origX, origY, calcX, calcY,
                            "Gateway", 50d, 50d
                    );
                    if (moId != null) nodeMap.put(moId, info);
                    nodeMap.put(elId, info);
                }

                parentSub.addChildElement(bpmnEl);

                FlowNodeInfo info = new FlowNodeInfo(
                        elId, origX, origY, calcX, calcY,
                        mapping.getType().getSimpleName(),
                        mapping.getHeight(), mapping.getWidth()
                );
                if (moId!=null) nodeMap.put(moId, info);

                DrawShape.drawShape(plane, model, bpmnEl,
                        calcX, calcY,
                        mapping.getHeight(), mapping.getWidth(),
                        true, false);

                // special "Wait" => Timer
                if (pxObjClass.startsWith("Data-MO-Activity-Assignment-Wait")
                        && bpmnEl instanceof IntermediateCatchEvent) {
                    TimerEventDefinition ted = model.newInstance(TimerEventDefinition.class);
                    ((IntermediateCatchEvent)bpmnEl).getEventDefinitions().add(ted);
                    // read pyMinutes
                    String minVal = getText(shapeEl, "pyMinutes");
                    if (minVal != null && !minVal.isEmpty()) {
                        TimeDuration dur = model.newInstance(TimeDuration.class);
                        dur.setTextContent("PT" + minVal + "M");
                        ted.setTimeDuration(dur);
                    }
                }
            }
        }
    }

    // Attempt to see if shape references a “flow name” from pyImplementation or pxLinkedRefTo
    private static String resolveFlowName(Element shapeEl, Document doc, XPath xpath) throws Exception {
        String moId = getText(shapeEl, "pyMOId");
        if (moId == null) return null;

        // from useCaseLinks first
        String linkExpr = "//pyUseCaseLinks/rowdata[pyTaskID='" + moId + "']/pxLinkedRefTo/text()";
        Node nd = (Node) xpath.compile(linkExpr).evaluate(doc, XPathConstants.NODE);
        if (nd != null) {
            return nd.getTextContent();
        }
        // fallback
        return getText(shapeEl, "pyImplementation");
    }

    // handle nested subflow: parse once if not in cache
    private static void handleNestedSubProcess(BpmnModelInstance model,
                                               SubProcess subEl,
                                               String flowName,
                                               File caseTypeFile,
                                               double baseX, double baseY,
                                               Map<String, FlowNodeInfo> nodeMap,
                                               BpmnPlane plane) throws Exception {
        if (subProcessCache.containsKey(flowName)) {
            return; // already populated
        }
        subProcessCache.put(flowName, subEl);

        File nestedFlowFile = findFlowFile(flowName, caseTypeFile);
        populateSubProcess(model, subEl, nestedFlowFile,
                baseX, baseY, plane, nodeMap, caseTypeFile);
    }

    // -------------------------------------------------------------------------
    // 4) Process Data-MO-Connector-Transition => SequenceFlows
    // -------------------------------------------------------------------------
    private static void processSequenceFlows(BpmnModelInstance model,
                                             SubProcess parentSub,
                                             Document flowDoc,
                                             XPath xpath,
                                             Map<String, FlowNodeInfo> nodeMap,
                                             BpmnPlane plane) throws Exception {

        NodeList cNodes = (NodeList) xpath.compile("//pxObjClass[text()='Data-MO-Connector-Transition']")
                .evaluate(flowDoc, XPathConstants.NODESET);

        for (int i=0; i<cNodes.getLength(); i++) {
            Element el = (Element)cNodes.item(i).getParentNode();
            String fromId = getText(el, "pyFrom");
            String toId   = getText(el, "pyTo");
            if (fromId==null || toId==null) continue;

            FlowNodeInfo finfo = nodeMap.get(fromId);
            FlowNodeInfo tinfo = nodeMap.get(toId);

            if (finfo != null && tinfo != null) {
                System.out.println("✅ Found nodes: " + fromId + " (" + finfo.getType() + ") → " + toId + " (" + tinfo.getType() + ")");
                FlowNode src = model.getModelElementById(finfo.getNewId());
                FlowNode tgt = model.getModelElementById(tinfo.getNewId());

                if (src != null && tgt != null) {
                    SequenceFlow sf = model.newInstance(SequenceFlow.class);
                    sf.setId("F_" + UUID.randomUUID().toString().replace("-", ""));
                    sf.setSource(src);
                    sf.setTarget(tgt);

                    // Ensure the sequence flow is added
                    System.out.println("🔗 Adding sequence flow: " + sf.getId());
                    parentSub.addChildElement(sf);

                    // Draw the flow
                    NodeList connectorPoints = el.getElementsByTagName("pyConnectorPoints");
                    DrawFlow.drawFlow(plane, model, sf, finfo, tinfo, connectorPoints, 0d, 0d);
                } else {
                    System.out.println("⚠️ Warning: Source or Target node is null in BPMN model.");
                }
            } else {
                System.out.println("🚨 ERROR: Missing source or target node in nodeMap: " + fromId + " → " + toId);
            }

        }
    }

    // -------------------------------------------------------------------------
    // 5) Process useCaseLinks => possibly references SubProcess
    // -------------------------------------------------------------------------
    private static void processUseCaseLinks(BpmnModelInstance model,
                                            SubProcess parentSub,
                                            Document flowDoc,
                                            XPath xpath,
                                            Map<String, FlowNodeInfo> nodeMap,
                                            BpmnPlane plane) throws Exception {

        NodeList linkList = (NodeList) xpath.compile("//pyUseCaseLinks/rowdata")
                .evaluate(flowDoc, XPathConstants.NODESET);

        for (int i=0; i<linkList.getLength(); i++) {
            Element row = (Element) linkList.item(i);
            String sourceId   = getText(row, "pyTaskID");
            String targetFlow = getText(row, "pxLinkedRefTo");
            if (targetFlow == null) continue;

            String targetId   = flowNameToIdMap.get(targetFlow);

            FlowNodeInfo sinfo = nodeMap.get(sourceId);
            FlowNodeInfo tinfo = nodeMap.get(targetId);
            if (sinfo!=null && tinfo!=null) {
                FlowNode src = model.getModelElementById(sinfo.getNewId());
                FlowNode tgt = model.getModelElementById(tinfo.getNewId());
                if (src!=null && tgt!=null) {
                    SequenceFlow sf = model.newInstance(SequenceFlow.class);
                    sf.setId("SF_" + src.getId() + "_" + tgt.getId());
                    sf.setSource(src);
                    sf.setTarget(tgt);
                    parentSub.addChildElement(sf);

                    // optional drawing
                    DrawFlow.drawFlow(plane, model, sf, sinfo, tinfo, null,0d,0d);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chain sub-processes at the top (Process) level
    // -------------------------------------------------------------------------
    private static void connectTopLevel(BpmnModelInstance model,
                                        Process parent,
                                        BpmnPlane plane,
                                        Map<String, FlowNodeInfo> nodeMap,
                                        FlowNode source,
                                        FlowNode target) {
        SequenceFlow sf = model.newInstance(SequenceFlow.class);
        sf.setId("SF_" + UUID.randomUUID().toString().replace("-", ""));
        sf.setSource(source);
        sf.setTarget(target);
        parent.addChildElement(sf);

        FlowNodeInfo sinfo = nodeMap.get(source.getId());
        FlowNodeInfo tinfo = nodeMap.get(target.getId());
        if (sinfo!=null && tinfo!=null) {
            DrawFlow.drawFlow(plane, model, sf, sinfo, tinfo, null, 0d,0d);
        }
    }

    // -------------------------------------------------------------------------
    //  Utilities
    // -------------------------------------------------------------------------
    private static PegaToBPMNElement guessBpmnShape(String pxObjClass) {
        if (pxObjClass == null) pxObjClass = "";

        // Gateways
        if (pxObjClass.startsWith("Data-MO-Gateway-")) {
            // e.g. DataXOR => ExclusiveGateway
            return new PegaToBPMNElement(ExclusiveGateway.class, 50d, 50d);
        }
        // SubProcess
        if (pxObjClass.startsWith("Data-MO-Activity-SubProcess")) {
            return new PegaToBPMNElement(SubProcess.class, 80d,100d);
        }
        // Wait assignment => IntermediateCatchEvent
        if (pxObjClass.startsWith("Data-MO-Activity-Assignment-Wait")) {
            return new PegaToBPMNElement(IntermediateCatchEvent.class, 36d,36d);
        }
        // Assignment => user task
        if (pxObjClass.startsWith("Data-MO-Activity-Assignment")) {
            return new PegaToBPMNElement(UserTask.class,80d,100d);
        }
        // Start
        if (pxObjClass.equals("Data-MO-Event-Start")) {
            return new PegaToBPMNElement(StartEvent.class,36d,36d);
        }
        // End/Exception
        if (pxObjClass.startsWith("Data-MO-Event-End")
                || pxObjClass.startsWith("Data-MO-Event-Exception")) {
            return new PegaToBPMNElement(EndEvent.class,36d,36d);
        }
        // Router => service
        if (pxObjClass.equals("Data-MO-Activity-Router")) {
            return new PegaToBPMNElement(ServiceTask.class,80d,100d);
        }
        // Notify => sendTask
        if (pxObjClass.equals("Data-MO-Activity-Notify")) {
            return new PegaToBPMNElement(SendTask.class,80d,100d);
        }
        // Utility => service
        if (pxObjClass.equals("Data-MO-Activity-Utility")) {
            return new PegaToBPMNElement(ServiceTask.class,80d,100d);
        }

        if (pxObjClass.equals("Data-MO-Activity-Integrator")) {
            // Map integrator calls to a ServiceTask as well
            return new PegaToBPMNElement(ServiceTask.class, 80d, 100d);
        }
        // fallback => service
        return new PegaToBPMNElement(ServiceTask.class,80d,100d);
    }

    private static File findFlowFile(String flowName, File caseTypeFile) {
        File parentDir = caseTypeFile.getParentFile();
        File flowFile = new File(parentDir, flowName + ".xml");
        if (!flowFile.exists()) {
            throw new IllegalArgumentException("Flow file not found: " + flowFile);
        }
        return flowFile;
    }

    private static String getText(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() > 0) {
            return nl.item(0).getTextContent();
        }
        return null;
    }
}
