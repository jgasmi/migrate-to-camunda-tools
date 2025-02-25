package org.camunda.bpmn.generator;

import io.camunda.zeebe.model.bpmn.instance.BpmnModelElementInstance;

public class PegaToBPMNElement {
    private Class<? extends BpmnModelElementInstance> type;
    private Double width;
    private Double height;

    public PegaToBPMNElement(Class<? extends BpmnModelElementInstance> type, Double width, Double height) {
        this.type = type;
        this.width = width;
        this.height = height;
    }

    public Class<? extends BpmnModelElementInstance> getType() { return type; }
    public Double getWidth() { return width; }
    public Double getHeight() { return height; }
}