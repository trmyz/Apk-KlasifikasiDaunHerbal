package com.example.klasifikasicitra;

public class ClassifierResult {
    public String label;
    public int confidencePercent;
    public float margin;
    public double normalizedEntropy;

    public ClassifierResult(String label, int confidencePercent, float margin, double normalizedEntropy) {
        this.label = label;
        this.confidencePercent = confidencePercent;
        this.margin = margin;
        this.normalizedEntropy = normalizedEntropy;
    }
}