/*
 * Copyright (c) 2015-2021, Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tribuo.regression.slm;

import ai.onnx.proto.OnnxMl;
import com.oracle.labs.mlrg.olcut.util.Pair;
import org.tribuo.Example;
import org.tribuo.Excuse;
import org.tribuo.ImmutableFeatureMap;
import org.tribuo.ImmutableOutputInfo;
import org.tribuo.Model;
import org.tribuo.ONNXExportable;
import org.tribuo.Prediction;
import org.tribuo.VariableInfo;
import org.tribuo.math.la.DenseVector;
import org.tribuo.math.la.SparseVector;
import org.tribuo.math.la.VectorTuple;
import org.tribuo.provenance.ModelProvenance;
import org.tribuo.provenance.TrainerProvenance;
import org.tribuo.regression.ImmutableRegressionInfo;
import org.tribuo.regression.Regressor;
import org.tribuo.regression.Regressor.DimensionTuple;
import org.tribuo.regression.impl.SkeletalIndependentRegressionSparseModel;
import org.tribuo.util.onnx.ONNXContext;
import org.tribuo.util.onnx.ONNXNode;
import org.tribuo.util.onnx.ONNXOperators;
import org.tribuo.util.onnx.ONNXPlaceholder;
import org.tribuo.util.onnx.ONNXRef;
import org.tribuo.util.onnx.ONNXInitializer;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.logging.Logger;

/**
 * The inference time version of a sparse linear regression model.
 * <p>
 * The type of the model depends on the trainer used.
 */
public class SparseLinearModel extends SkeletalIndependentRegressionSparseModel implements ONNXExportable {
    private static final long serialVersionUID = 3L;
    private static final Logger logger = Logger.getLogger(SparseLinearModel.class.getName());

    private SparseVector[] weights;
    private final DenseVector featureMeans;
    private final DenseVector featureVariance;
    private final boolean bias;
    private double[] yMean;
    private double[] yVariance;

    // Used to signal if the model has been rewritten to fix the issue with ElasticNet models in 4.0 and 4.1.0.
    private boolean enet41MappingFix;

    SparseLinearModel(String name, String[] dimensionNames, ModelProvenance description,
                      ImmutableFeatureMap featureIDMap, ImmutableOutputInfo<Regressor> labelIDMap,
                      SparseVector[] weights, DenseVector featureMeans, DenseVector featureVariance, double[] yMean, double[] yVariance, boolean bias) {
        super(name, dimensionNames, description, featureIDMap, labelIDMap, generateActiveFeatures(dimensionNames, featureIDMap, weights));
        this.weights = weights;
        this.featureMeans = featureMeans;
        this.featureVariance = featureVariance;
        this.bias = bias;
        this.yVariance = yVariance;
        this.yMean = yMean;
        this.enet41MappingFix = true;
    }

    private static Map<String, List<String>> generateActiveFeatures(String[] dimensionNames, ImmutableFeatureMap featureMap, SparseVector[] weightsArray) {
        Map<String, List<String>> map = new HashMap<>();

        for (int i = 0; i < dimensionNames.length; i++) {
            List<String> featureNames = new ArrayList<>();
            for (VectorTuple v : weightsArray[i]) {
                if (v.index == featureMap.size()) {
                    featureNames.add(BIAS_FEATURE);
                } else {
                    VariableInfo info = featureMap.get(v.index);
                    featureNames.add(info.getName());
                }
            }
            map.put(dimensionNames[i], featureNames);
        }

        return map;
    }

    /**
     * Creates the feature vector. Includes a bias term if the model requires it.
     *
     * @param example The example to convert.
     * @return The feature vector.
     */
    @Override
    protected SparseVector createFeatures(Example<Regressor> example) {
        SparseVector features = SparseVector.createSparseVector(example, featureIDMap, bias);
        features.intersectAndAddInPlace(featureMeans, (a) -> -a);
        features.hadamardProductInPlace(featureVariance, (a) -> 1.0 / a);
        return features;
    }

    @Override
    protected DimensionTuple scoreDimension(int dimensionIdx, SparseVector features) {
        double prediction = weights[dimensionIdx].numActiveElements() > 0 ? weights[dimensionIdx].dot(features) : 1.0;
        prediction *= yVariance[dimensionIdx];
        prediction += yMean[dimensionIdx];
        return new DimensionTuple(dimensions[dimensionIdx], prediction);
    }

    @Override
    public Map<String, List<Pair<String, Double>>> getTopFeatures(int n) {
        int maxFeatures = n < 0 ? featureIDMap.size() + 1 : n;

        Comparator<Pair<String, Double>> comparator = Comparator.comparingDouble(p -> Math.abs(p.getB()));

        //
        // Use a priority queue to find the top N features.
        Map<String, List<Pair<String, Double>>> map = new HashMap<>();
        PriorityQueue<Pair<String, Double>> q = new PriorityQueue<>(maxFeatures, comparator);

        for (int i = 0; i < dimensions.length; i++) {
            q.clear();
            for (VectorTuple v : weights[i]) {
                VariableInfo info = featureIDMap.get(v.index);
                String name = info == null ? BIAS_FEATURE : info.getName();
                Pair<String, Double> curr = new Pair<>(name, v.value);

                if (q.size() < maxFeatures) {
                    q.offer(curr);
                } else if (comparator.compare(curr, q.peek()) > 0) {
                    q.poll();
                    q.offer(curr);
                }
            }

            ArrayList<Pair<String, Double>> b = new ArrayList<>();
            while (q.size() > 0) {
                b.add(q.poll());
            }

            Collections.reverse(b);
            map.put(dimensions[i], b);
        }

        return map;
    }

    @Override
    public Optional<Excuse<Regressor>> getExcuse(Example<Regressor> example) {
        Prediction<Regressor> prediction = predict(example);
        Map<String, List<Pair<String, Double>>> weightMap = new HashMap<>();

        SparseVector features = createFeatures(example);
        for (int i = 0; i < dimensions.length; i++) {
            List<Pair<String, Double>> classScores = new ArrayList<>();
            for (VectorTuple f : features) {
                double score = weights[i].get(f.index) * f.value;
                classScores.add(new Pair<>(featureIDMap.get(f.index).getName(), score));
            }
            classScores.sort((Pair<String, Double> o1, Pair<String, Double> o2) -> o2.getB().compareTo(o1.getB()));
            weightMap.put(dimensions[i], classScores);
        }

        return Optional.of(new Excuse<>(example, prediction, weightMap));
    }

    @Override
    protected Model<Regressor> copy(String newName, ModelProvenance newProvenance) {
        return new SparseLinearModel(newName, Arrays.copyOf(dimensions, dimensions.length),
                newProvenance, featureIDMap, outputIDInfo,
                copyWeights(),
                featureMeans.copy(), featureVariance.copy(),
                Arrays.copyOf(yMean, yMean.length), Arrays.copyOf(yVariance, yVariance.length), bias);
    }

    private SparseVector[] copyWeights() {
        SparseVector[] newWeights = new SparseVector[weights.length];

        for (int i = 0; i < weights.length; i++) {
            newWeights[i] = weights[i].copy();
        }

        return newWeights;
    }

    /**
     * Gets a copy of the model parameters.
     *
     * @return A map from the dimension name to the model parameters.
     */
    public Map<String, SparseVector> getWeights() {
        SparseVector[] newWeights = copyWeights();
        Map<String, SparseVector> output = new HashMap<>();
        for (int i = 0; i < dimensions.length; i++) {
            output.put(dimensions[i], newWeights[i]);
        }
        return output;
    }

    @Override
    public OnnxMl.ModelProto exportONNXModel(String domain, long modelVersion) {
        ONNXContext onnx = new ONNXContext();

        ONNXPlaceholder input = onnx.floatInput(featureIDMap.size());
        ONNXPlaceholder output = onnx.floatOutput(outputIDInfo.size());
        onnx.setName("Regression-SparseLinearModel");

        return ONNXExportable.buildModel(writeONNXGraph(input).assignTo(output).onnxContext(), domain, modelVersion, this);
    }

    @Override
    public ONNXNode writeONNXGraph(ONNXRef<?> input) {
        ONNXContext onnx = input.onnxContext();

        // Add weights
        ONNXInitializer onnxWeights = onnx.floatTensor("slm_weights", Arrays.asList(featureIDMap.size(), outputIDInfo.size()),
                fb -> {
                    for (int j = 0; j < featureIDMap.size(); j++) {
                        for (int i = 0; i < weights.length; i++) {
                            fb.put((float) weights[i].get(j));
                        }
                    }
                });

        // Add biases
        ONNXInitializer onnxBiases = onnx.floatTensor("slm_biases", Collections.singletonList(outputIDInfo.size()),
                (FloatBuffer fb) -> Arrays.stream(weights).forEachOrdered(sv -> fb.put((float) sv.get(featureIDMap.size()))));

        // Add feature and output means
        double[] xMean = bias ? Arrays.copyOf(featureMeans.toArray(), featureIDMap.size()) : featureMeans.toArray();

        ONNXInitializer featureMean = onnx.array("feature_mean", xMean);
        ONNXInitializer outputMean = onnx.array("y_mean", yMean);

        // Add feature and output variances
        double[] xVariance = bias ? Arrays.copyOf(featureVariance.toArray(),featureIDMap.size()) : featureVariance.toArray();
        ONNXInitializer featureVariance = onnx.array("feature_variance", xVariance);
        ONNXInitializer outputVariance = onnx.array("y_variance", yVariance);

        // Scale features
        ONNXNode scaledFeatures = input.apply(ONNXOperators.SUB, featureMean)
                .apply(ONNXOperators.DIV, featureVariance);

        // Make gemm
        ONNXNode gemm = scaledFeatures.apply(ONNXOperators.GEMM, Arrays.asList(onnxWeights, onnxBiases));

        // Scale outputs
        return gemm.apply(ONNXOperators.MUL, outputVariance)
                .apply(ONNXOperators.ADD, outputMean);
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();

        // Rearrange the dimensions in ElasticNet models from 4.1.0 and earlier because they are corrupted.
        String tribuoVersion = (String) provenance.getTrainerProvenance().getInstanceValues().get(TrainerProvenance.TRIBUO_VERSION_STRING).getValue();
        if (provenance.getTrainerProvenance().getClassName().equals("org.tribuo.regression.slm.ElasticNetCDTrainer") && !enet41MappingFix &&
                (tribuoVersion.startsWith("4.0.0") || tribuoVersion.startsWith("4.0.1") || tribuoVersion.startsWith("4.0.2") || tribuoVersion.startsWith("4.1.0")
                        // This is explicit to catch the test model which has a 4.1.1-SNAPSHOT Tribuo version.
                        || tribuoVersion.equals("4.1.1-SNAPSHOT"))) {
            enet41MappingFix = true;
            int[] mapping = ((ImmutableRegressionInfo) outputIDInfo).getIDtoNaturalOrderMapping();
            SparseVector[] newWeights = new SparseVector[weights.length];
            double[] newYMeans = new double[weights.length];
            double[] newYVariances = new double[weights.length];

            for (int i = 0; i < mapping.length; i++) {
                newWeights[i] = this.weights[mapping[i]];
                newYMeans[i] = this.yMean[mapping[i]];
                newYVariances[i] = this.yVariance[mapping[i]];
            }

            this.yMean = newYMeans;
            this.yVariance = newYVariances;
            this.weights = newWeights;
        }
    }
}
