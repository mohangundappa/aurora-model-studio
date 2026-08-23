package com.aurora.studio.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "studio.confidence")
public class ConfidenceWeights {
  private double sourceReliability = 0.25;
  private double crossSourceAgreement = 0.20;
  private double extractionCertainty = 0.20;
  private double completeness = 0.15;
  private double recency = 0.10;
  private double executionEvidence = 0.10;

  public double sourceReliability() {
    return sourceReliability;
  }

  public void setSourceReliability(double sourceReliability) {
    this.sourceReliability = sourceReliability;
  }

  public double crossSourceAgreement() {
    return crossSourceAgreement;
  }

  public void setCrossSourceAgreement(double crossSourceAgreement) {
    this.crossSourceAgreement = crossSourceAgreement;
  }

  public double extractionCertainty() {
    return extractionCertainty;
  }

  public void setExtractionCertainty(double extractionCertainty) {
    this.extractionCertainty = extractionCertainty;
  }

  public double completeness() {
    return completeness;
  }

  public void setCompleteness(double completeness) {
    this.completeness = completeness;
  }

  public double recency() {
    return recency;
  }

  public void setRecency(double recency) {
    this.recency = recency;
  }

  public double executionEvidence() {
    return executionEvidence;
  }

  public void setExecutionEvidence(double executionEvidence) {
    this.executionEvidence = executionEvidence;
  }
}
