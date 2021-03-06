/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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

package org.tribuo.common.sgd;

import ai.onnx.proto.OnnxMl;
import com.oracle.labs.mlrg.olcut.util.Pair;
import org.tribuo.Example;
import org.tribuo.Excuse;
import org.tribuo.Feature;
import org.tribuo.ImmutableFeatureMap;
import org.tribuo.ImmutableOutputInfo;
import org.tribuo.Model;
import org.tribuo.ONNXExportable;
import org.tribuo.Output;
import org.tribuo.Prediction;
import org.tribuo.math.LinearParameters;
import org.tribuo.math.la.DenseMatrix;
import org.tribuo.math.la.Matrix;
import org.tribuo.provenance.ModelProvenance;
import org.tribuo.util.onnx.ONNXContext;
import org.tribuo.util.onnx.ONNXInitializer;
import org.tribuo.util.onnx.ONNXNode;
import org.tribuo.util.onnx.ONNXOperators;
import org.tribuo.util.onnx.ONNXPlaceholder;
import org.tribuo.util.onnx.ONNXRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * A linear model trained using SGD.
 * <p>
 * It's an {@link AbstractSGDModel} containing a {@link LinearParameters}, with
 * the bias folded into the features.
 * <p>
 * See:
 * <pre>
 * Bottou L.
 * "Large-Scale Machine Learning with Stochastic Gradient Descent"
 * Proceedings of COMPSTAT, 2010.
 * </pre>
 */
public abstract class AbstractLinearSGDModel<T extends Output<T>> extends AbstractSGDModel<T> {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a linear model trained via SGD.
     * @param name The model name.
     * @param provenance The model provenance.
     * @param featureIDMap The feature domain.
     * @param outputIDInfo The output domain.
     * @param parameters The model parameters.
     * @param generatesProbabilities Does this model generate probabilities?
     */
    protected AbstractLinearSGDModel(String name, ModelProvenance provenance,
                                     ImmutableFeatureMap featureIDMap, ImmutableOutputInfo<T> outputIDInfo,
                                     LinearParameters parameters, boolean generatesProbabilities) {
        super(name, provenance, featureIDMap, outputIDInfo, parameters, generatesProbabilities, true);
    }

    @Override
    public Map<String, List<Pair<String, Double>>> getTopFeatures(int n) {
        DenseMatrix baseWeights = (DenseMatrix) modelParameters.get()[0];
        int maxFeatures = n < 0 ? featureIDMap.size() + 1 : n;

        Comparator<Pair<String,Double>> comparator = Comparator.comparingDouble(p -> Math.abs(p.getB()));

        //
        // Use a priority queue to find the top N features.
        int numClasses = baseWeights.getDimension1Size();
        int numFeatures = baseWeights.getDimension2Size()-1; //Removing the bias feature.
        Map<String, List<Pair<String,Double>>> map = new HashMap<>();
        for (int i = 0; i < numClasses; i++) {
            PriorityQueue<Pair<String,Double>> q = new PriorityQueue<>(maxFeatures, comparator);

            for (int j = 0; j < numFeatures; j++) {
                Pair<String,Double> curr = new Pair<>(featureIDMap.get(j).getName(), baseWeights.get(i,j));

                if (q.size() < maxFeatures) {
                    q.offer(curr);
                } else if (comparator.compare(curr, q.peek()) > 0) {
                    q.poll();
                    q.offer(curr);
                }
            }
            Pair<String,Double> curr = new Pair<>(BIAS_FEATURE, baseWeights.get(i,numFeatures));

            if (q.size() < maxFeatures) {
                q.offer(curr);
            } else if (comparator.compare(curr, q.peek()) > 0) {
                q.poll();
                q.offer(curr);
            }
            List<Pair<String,Double>> b = new ArrayList<>();
            while (q.size() > 0) {
                b.add(q.poll());
            }

            Collections.reverse(b);
            map.put(getDimensionName(i), b);
        }
        return map;
    }

    @Override
    public Optional<Excuse<T>> getExcuse(Example<T> example) {
        DenseMatrix baseWeights = (DenseMatrix) modelParameters.get()[0];
        Prediction<T> prediction = predict(example);
        Map<String, List<Pair<String, Double>>> weightMap = new HashMap<>();
        int numClasses = baseWeights.getDimension1Size();
        int numFeatures = baseWeights.getDimension2Size()-1;

        for (int i = 0; i < numClasses; i++) {
            List<Pair<String, Double>> classScores = new ArrayList<>();
            for (Feature f : example) {
                int id = featureIDMap.getID(f.getName());
                if (id > -1) {
                    double score = baseWeights.get(i,id) * f.getValue();
                    classScores.add(new Pair<>(f.getName(), score));
                }
            }
            classScores.add(new Pair<>(Model.BIAS_FEATURE, baseWeights.get(i,numFeatures)));
            classScores.sort((Pair<String, Double> o1, Pair<String, Double> o2) -> o2.getB().compareTo(o1.getB()));
            weightMap.put(getDimensionName(i), classScores);
        }

        return Optional.of(new Excuse<>(example, prediction, weightMap));
    }

    /**
     * Gets the name of the indexed output dimension.
     * @param index The output dimension index.
     * @return The name of the requested output dimension.
     */
    protected abstract String getDimensionName(int index);

    /**
     * Returns a copy of the weights.
     * @return A copy of the weights.
     */
    public DenseMatrix getWeightsCopy() {
        return ((DenseMatrix)modelParameters.get()[0]).copy();
    }

    /**
     * Takes the unnormalized ONNX output of this model and applies an appropriate normalizer from the concrete class.
     * @param input Unnormalized ONNX leaf node.
     * @return Normalized ONNX leaf node.
     */
    protected abstract ONNXNode onnxOutput(ONNXNode input);

    /**
     * @return Name to write into the ONNX Model.
     */
    protected abstract String onnxModelName();

    /**
     * Writes this {@link org.tribuo.Model} into {@link OnnxMl.GraphProto.Builder} inside the input's
     * {@link ONNXContext}.
     * @param input The input to the model graph.
     * @return the output node of the model graph.
     */
    public ONNXNode writeONNXGraph(ONNXRef<?> input) {
        ONNXContext onnx = input.onnxContext();

        Matrix weightMatrix = (Matrix) modelParameters.get()[0];

        ONNXInitializer weights = onnx.floatTensor("linear_sgd_weights", Arrays.asList(featureIDMap.size(), outputIDInfo.size()), fb -> {
            for (int j = 0; j < weightMatrix.getDimension2Size() - 1; j++) {
                for (int i = 0; i < weightMatrix.getDimension1Size(); i++) {
                    fb.put((float) weightMatrix.get(i, j));
                }
            }
        });
        ONNXInitializer bias = onnx.floatTensor("linear_sgd_bias", Collections.singletonList(outputIDInfo.size()), fb -> {
            for (int i = 0; i < weightMatrix.getDimension1Size(); i++) {
                fb.put((float)weightMatrix.get(i,weightMatrix.getDimension2Size()-1));
            }
        });

        return onnxOutput(input.apply(ONNXOperators.GEMM, Arrays.asList(weights, bias)));
    }

    /**
     * Exports this {@link org.tribuo.Model} as an ONNX protobuf.
     * @param domain A reverse-DNS name to namespace the model (e.g., org.tribuo.classification.sgd.linear).
     * @param modelVersion A version number for this model.
     * @return The ONNX ModelProto representing this Tribuo Model.
     */
    public OnnxMl.ModelProto exportONNXModel(String domain, long modelVersion) {
        ONNXContext onnx = new ONNXContext();
        onnx.setName(onnxModelName());
        ONNXPlaceholder input = onnx.floatInput("input", featureIDMap.size());
        ONNXPlaceholder output = onnx.floatOutput("output", outputIDInfo.size());
        writeONNXGraph(input).assignTo(output);
        return ONNXExportable.buildModel(onnx, domain, modelVersion, this);
    }

}
