/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.usc.irds.agepredictor.cmdline.spark.authorage;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.ml.feature.CountVectorizerModel;
import org.apache.spark.ml.feature.Normalizer;
import org.apache.spark.ml.linalg.SparseVector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LassoModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import edu.usc.irds.agepredictor.cmdline.CLI;
import edu.usc.irds.agepredictor.spark.authorage.AgePredictModel;
import opennlp.tools.authorage.AgeClassifyME;
import opennlp.tools.authorage.AgeClassifyModel;
import opennlp.tools.cmdline.BasicCmdLineTool;
import opennlp.tools.cmdline.CmdLineUtil;
import opennlp.tools.cmdline.SystemInputStreamFactory;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.ParagraphStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.featuregen.FeatureGenerator;

/**
 * TODO: Documentation
 */
public class AgePredictTool extends BasicCmdLineTool implements Serializable {

	@Override
	public String getShortDescription() {
		return "age predictor";
	}

	@Override
	public String getHelp() {
		return "Usage: " + CLI.CMD + " " + getName() + " [MaxEntModel] RegressionModel Documents";
	}

	@Override
	public void run(String[] args) {
		AgePredictModel model = null;
		AgeClassifyME classify = null;
		if (args.length == 3) {
			try {
				AgeClassifyModel classifyModel = new AgeClassifyModel(new File(args[0]));

				classify = new AgeClassifyME(classifyModel);
				model = AgePredictModel.readModel(new File(args[1]));
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
		} else if (args.length == 2) {
			try {
				model = AgePredictModel.readModel(new File(args[0]));
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
		} else {
			System.out.println(getHelp());
			return;
		}

		ObjectStream<String> documentStream;
		List<Row> data = new ArrayList<Row>();

		SparkSession spark = SparkSession.builder().appName("AgePredict").getOrCreate();
				
		try {
			System.out.println("Please enter your text separted by newline. When done press ctrl+d to terminate system input");
			documentStream = new ParagraphStream(
					new PlainTextByLineStream(new SystemInputStreamFactory(), SystemInputStreamFactory.encoding()));

			String document;
			FeatureGenerator[] featureGenerators = model.getContext().getFeatureGenerators();
			while ((document = documentStream.read()) != null) {
				String[] tokens = model.getContext().getTokenizer().tokenize(document);

				double prob[] = classify.getProbabilities(tokens);
				String category = classify.getBestCategory(prob);

				Collection<String> context = new ArrayList<String>();

				for (FeatureGenerator featureGenerator : featureGenerators) {
					Collection<String> extractedFeatures = featureGenerator.extractFeatures(tokens);
					context.addAll(extractedFeatures);
				}

				if (category != null) {
					for (int i = 0; i < tokens.length / 18; i++) {
						context.add("cat=" + category);
					}
				}
				if (context.size() > 0) {
					data.add(RowFactory.create(document, context.toArray()));
				}
				
			}
		} catch (IOException e) {
			e.printStackTrace();
			CmdLineUtil.handleStdinIoError(e);
		}
				
		StructType schema = new StructType(
				new StructField[] { new StructField("document", DataTypes.StringType, false, Metadata.empty()),
						new StructField("text", new ArrayType(DataTypes.StringType, true), false, Metadata.empty()) });

		Dataset<Row> df = spark.createDataFrame(data, schema);

		CountVectorizerModel cvm = new CountVectorizerModel(model.getVocabulary()).setInputCol("text")
				.setOutputCol("feature");

		Dataset<Row> eventDF = cvm.transform(df);

		Normalizer normalizer = new Normalizer().setInputCol("feature").setOutputCol("normFeature").setP(1.0);

		JavaRDD<Row> normEventDF = normalizer.transform(eventDF).javaRDD();

		//org.apache.spark.ml.linalg.SparseVector cannot be cast to org.apache.spark.mllib.linalg.Vector

		final LassoModel linModel = model.getModel();
		normEventDF.foreach(new VoidFunction<Row>() {
			public void call(Row event) {
				SparseVector sp = (SparseVector) event.getAs("normFeature");
				
				double prediction = linModel.predict(Vectors.sparse(sp.size(), sp.indices(), sp.values()));
				System.out.println((String) event.getAs("document"));
				System.out.println("Prediction: " + prediction);
			}
		});

		spark.stop();
	}
}
