package process;

import jeigen.DenseMatrix;
import utils.Serializer;

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.*;

import static java.lang.Math.log;
import static process.features.FeatureExtractor.*;
import static utils.Serializer.readArrayFromDisk;
import static utils.Utils.*;

public class Classifier {
    /**
     * A Classifier maps an observation to a label valued in a finite set.
     * f(HaarFeaturesOfTheImage) = -1 or 1
     * CAUTION: the training only works on same-sized images!
     * This Classifier uses what is know as Strong & Weak classifiers
     * A Weak classifier is just a simple basic classifier, it could be Binary, Naive Bayes or anything else.
     * The only need for a weak classifier is to return results with a success rate > 0.5, which is actually better than random.
     * The combination of all these Weak classifier create of very good classifier, which is called the Strong classifier.
     * <p>
     * This is what we call Adaboosting.
     */

    /* --- CONSTANTS FOR TRAINING --- */
    private static final int POSITIVE = 0;
    private static final int NEGATIVE = 1;
    private static final float TWEAK_UNIT = 1e-2f;      // initial tweak unit
    private static final double MIN_TWEAK = 1e-5;       // tweak unit cannot go lower than this
    private static final double GOAL = 1e-7;
    private static final double FLAT_IMAGE_THRESHOLD = 1; // All pixels have the same color

    /* --- CLASS VARIABLES --- */

    private boolean computed = false;

    private final int width;
    private final int height;
    private long featureCount;

    private String train_dir;
    private ArrayList<String> trainFaces;
    private ArrayList<String> trainNonFaces;
    private String test_dir;
    private ArrayList<String> testFaces;
    private ArrayList<String> testNonFaces;

    private int countTrainPos;
    private int countTrainNeg;
    private int trainN;
    private int countTestPos;
    private int countTestNeg;
    private int testN;

    private DenseMatrix[] trainBlackList = new DenseMatrix[2];
    private DenseMatrix[] testBlackList = new DenseMatrix[2];


    private ArrayList<Integer> layerMemory;
    private ArrayList<StumpRule>[] cascade;
    private ArrayList<Float> tweaks;

    private DenseMatrix weightsTrain;
    private DenseMatrix weightsTest;
    private DenseMatrix labelsTrain;
    private DenseMatrix labelsTest;

    private double totalWeightPos; // total weight received by positive examples currently
    private double totalWeightNeg; // total weight received by negative examples currently

    private double minWeight; // minimum weight among all weights currently
    private double maxWeight; // maximum weight among all weights currently

    private final ExecutorService executor = Executors.newFixedThreadPool(Conf.TRAIN_MAX_CONCURENT_PROCESSES);

    public Classifier(int width, int height) {
        this.width = width;
        this.height = height;

        this.featureCount = countAllFeatures(width, height);
        System.out.println("Feature count for " + width + "x" + height + ": " + featureCount);
    }

    private static boolean isFace(ArrayList<StumpRule>[] cascade, ArrayList<Float> tweaks, ArrayList<Integer> exampleFeatureValues, int defaultLayerNumber) {
        long featureCount = exampleFeatureValues.size();

        double sum = 0;
        double sumSum = 0;
        for (Integer exampleFeatureValue : exampleFeatureValues) {
            sum += exampleFeatureValue;
            sumSum += exampleFeatureValue * exampleFeatureValue;
        }
        // standardDeviation = SQRT(VAR(X))
        double standardDeviation = Math.sqrt((sumSum/Math.pow((double)featureCount, 2)) - (Math.pow(sum/Math.pow((float)featureCount, 2), 2)));
        if (!Double.isFinite(standardDeviation) || standardDeviation < FLAT_IMAGE_THRESHOLD)
            return false;

        // Everything is a face if no layer is involved
        if (defaultLayerNumber == 0) {
            System.out.println("Does it really happen? It seems!");
            return true;
        }
        int layerCount = defaultLayerNumber < 0 ? tweaks.size() : defaultLayerNumber;
        for(int layer = 0; layer < layerCount; layer++){
            double prediction = 0;
            int committeeSize = cascade[layer].size();
            for(int ruleIndex = 0; ruleIndex < committeeSize; ruleIndex++){
                StumpRule rule = cascade[layer].get(ruleIndex);
                double featureValue = (double)exampleFeatureValues.get((int) rule.featureIndex) / standardDeviation;
                double vote = (featureValue > rule.threshold ? 1 : -1) * rule.toggle + tweaks.get(layer);
                if (rule.error == 0) {
                    if (ruleIndex == 0)
                        return vote > 0;
                    else {
                        System.err.println("Find an invalid rule!");
                        System.exit(1);
                    }
                }
                prediction += vote * log((1.0d/rule.error) - 1);
            }
            if (prediction < 0)
                return false;
        }
        return true;
    }

    /**
     * Used to compute results
     */
    private static DenseMatrix predictLabel(ArrayList<StumpRule> committee, int N, float decisionTweak, int startingFrom) {
        // prediction = Matrix<int, 1,n > -> To be filled here

        int committeeSize = committee.size();
        DenseMatrix memberVerdict = new DenseMatrix(committeeSize, N);
        DenseMatrix memberWeight = new DenseMatrix(1, committeeSize);

        // We compute results for the layer by comparing each StumpRule's threshold and each example.
        for (int member = startingFrom; member < committeeSize; member++) {
            if (committee.get(member).error == 0 && member != 0) {
                System.err.println("Boosting Error Occurred!");
                System.exit(1);
            }

            double err = committee.get(member).error;
            assert Double.isFinite(err); // <=> !NaN && !Infinity

            memberWeight.set(member, log(safeDiv(1.0d, err) - 1));

            long featureIndex = committee.get(member).featureIndex;
            final ArrayList<Integer> featureExamplesIndexes = getFeatureExamplesIndexes(featureIndex, N);
            final ArrayList<Integer> featureValues = getFeatureValues(featureIndex, N);

            for (int i = 0; i < N; i++)
                memberVerdict.set(member, featureExamplesIndexes.get(i),
                        ((featureValues.get(i) > committee.get(member).threshold ? 1 : -1) * committee.get(member).toggle) + decisionTweak);
        }
        DenseMatrix prediction = new DenseMatrix(1, N);
        if (startingFrom == 0) {
            // If we predict labels using all members of this layer, we have to weight memberVerdict.
            DenseMatrix finalVerdict = memberWeight.mmul(memberVerdict);
            for (int i = 0; i < N; i++)
                prediction.set(0, i, finalVerdict.get(0, i) > 0 ? 1 : -1);
        }
        else {
            for (int i = 0; i < N; i++)
                prediction.set(0, i, memberVerdict.get(startingFrom, i) > 0 ? 1 : -1);
        }
        return prediction;
    }

    /**
     * Algorithm 5 from the original paper
     *
     * Explication: We want to find the feature that gives the lowest error when separating positive and negative examples with that feature's threshold!
     *
     * Return the most discriminative feature and its rule
     * We compute each StumpRule, and find the one with:
     * - the lower weighted error first
     * - the wider margin
     *
     * Pair<Integer i, Boolean b> indicates whether feature i is a face (b=true) or not (b=false)
     */
    private StumpRule bestStump() {
        long startTime = System.currentTimeMillis();

        // Compare each StumpRule and find the best by following this algorithm:
        //   if (current.weightedError < best.weightedError) -> best = current
        //   else if (current.weightedError == best.weightedError && current.margin > best.margin) -> best = current

//        System.out.println("      - Calling bestStump with totalWeightsPos: " + totalWeightPos + " totalWeightNeg: " + totalWeightNeg + " minWeight: " + minWeight);
        ArrayList<Future<StumpRule>> futureResults = new ArrayList<>(trainN);
        for (int i = 0; i < featureCount; i++)
            futureResults.add(executor.submit(new DecisionStump(labelsTrain, weightsTrain, i, trainN, totalWeightPos, totalWeightNeg, minWeight)));

        StumpRule best = null;
        for (int i = 0; i < featureCount; i++) {
            try {
                StumpRule current = futureResults.get(i).get();
                if (best == null)
                    best = current;
                else if (current.compare(best))
                        best = current;
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        if (best.error >= 0.5) {
            System.out.println("      - Failed best stump, error : " + best.error + " >= 0.5 !");
            System.exit(1);
        }

        System.out.println("      - Found best stump in " + ((new Date()).getTime() - startTime)/1000 + "s" +
                " : (featureIdx: " + best.featureIndex +
                ", threshold: " + best.threshold +
                ", margin: " + best.margin +
                ", error: " + best.error +
                ", toggle: " + best.toggle + ")");
        return best;
    }

    /**
     * Algorithm 6 from the original paper
     *
     * Strong classifier based on multiple weak classifiers.
     * Here, weak classifier are called "Stumps", see: https://en.wikipedia.org/wiki/Decision_stump
     *
     * Explication: The training aims to find the feature with the threshold that will allows to separate positive & negative examples in the best way possible!
     */
    private void adaboost(int round) {
        // STATE: OK & CHECKED 16/31/08

        StumpRule bestDS = bestStump(); // A new weak classifier
        cascade[round].add(bestDS); // Add this weak classifier to our current strong classifier to get better results

        DenseMatrix predictions = predictLabel(cascade[round], trainN, 0, cascade[round].size()-1);

        DenseMatrix agree = labelsTrain.mul(predictions);
        DenseMatrix weightUpdate = DenseMatrix.ones(1, trainN); // new ArrayList<>(trainN);

        boolean werror = false;

        for (int i = 0; i < trainN; i++) {
            if (agree.get(0, i) < 0) {
                if (bestDS.error != 0)
                    weightUpdate.set(0, i, (1 / bestDS.error) - 1); // (1 / bestDS.error) - 1
                else
                    weightUpdate.set(0, i, Double.MAX_VALUE - 1);
                werror = true;
            }
        }

        //
        if (werror) {

            weightsTrain = weightsTrain.mul(weightUpdate);
            /*for (int i = 0; i < trainN; i++)
                weightsTrain.set(i, weightsTrain.get(i) * weightUpdate.get(i));
*/
            double sum = 0;
            for (int i = 0; i < trainN; i++)
                sum += weightsTrain.get(0, i);

            double sumPos = 0;

            minWeight = 1;
            maxWeight = 0;

            for (int i = 0; i < trainN; i++) {
                double newVal = weightsTrain.get(0, i) / sum;
                weightsTrain.set(0, i, newVal);
                if (i < countTrainPos)
                    sumPos += newVal;
                if (minWeight > newVal)
                    minWeight = newVal;
                if (maxWeight < newVal)
                    maxWeight = newVal;
            }
            totalWeightPos = sumPos;
            totalWeightNeg = 1 - sumPos;

            assert totalWeightPos + totalWeightNeg == 1;
            assert totalWeightPos <= 1;
            assert totalWeightNeg <= 1;
        }
    }

    // p141 in paper?
    private double[] calcEmpiricalError(boolean training, int round) {
        // STATE: OK & CHECKED 16/26/08

        double[] res = new double[2];

        int nFalsePositive = 0;
        int nFalseNegative = 0;

        if (training) {
            trainBlackList[POSITIVE] = DenseMatrix.zeros(1, countTrainPos);
            trainBlackList[NEGATIVE] = DenseMatrix.ones(1, countTrainNeg);

            DenseMatrix verdicts = DenseMatrix.ones(1, trainN);
            for (int layer = 0; layer < round+1; layer++) {
                DenseMatrix predictions = predictLabel(cascade[layer], trainN, tweaks.get(layer), 0);
                verdicts = verdicts.min(predictions); // Those at -1, remain where you are!
            }

            // Evaluate prediction errors
            DenseMatrix agree = labelsTrain.mul(verdicts);
            for (int exampleIndex = 0; exampleIndex < trainN; exampleIndex++) {
                if (agree.get(0, exampleIndex) < 0) {
                    if (exampleIndex < countTrainPos) {
                        trainBlackList[POSITIVE].set(0, exampleIndex, 1);
                        nFalseNegative += 1;
                    } else {
                        trainBlackList[NEGATIVE].set(0, exampleIndex-countTrainPos, 0);
                        nFalsePositive += 1;
                    }
                }
            }
            res[0] = nFalsePositive / (double) countTrainNeg;
            res[1] = nFalseNegative / (double) countTrainPos;
        }
        else {
            testBlackList[POSITIVE] = DenseMatrix.zeros(1, countTestPos);
            testBlackList[NEGATIVE] = DenseMatrix.ones(1, countTestNeg);

            for (int i = 0; i < countTestPos; i++) {
                boolean face = isFace(cascade, tweaks, readArrayFromDisk(testFaces.get(i)), round+1);
                if (!face) {
                    testBlackList[POSITIVE].set(0, i, 1);
                    nFalseNegative += 1;
                }
            }
            for (int i = 0; i < countTestNeg; i++) {
                boolean face = isFace(cascade, tweaks, readArrayFromDisk(testNonFaces.get(i)), round+1);
                if (face) {
                    testBlackList[NEGATIVE].set(0, i, 0);
                    nFalsePositive += 1;
                }
            }
            res[0] = nFalsePositive / (double) countTestNeg;
            res[1] = nFalseNegative / (double) countTestPos;
        }

        return res;
    }

    /**
     * Algorithm 10 from the original paper
     */
    private int attentionalCascade(int round, float overallTargetDetectionRate, float overallTargetFalsePositiveRate) {
        // STATE: OK & CHECKED 16/31/08

        int committeeSizeGuide = Math.min(20 + round * 10, 200);
        System.out.println("    - CommitteeSizeGuide = " + committeeSizeGuide);

        cascade[round] = new ArrayList<>();

        int nbWeakClassifier = 0;
        boolean layerMissionAccomplished = false;
        while (!layerMissionAccomplished) {

            // Run algorithm N°6 (adaboost) to produce a classifier (== ArrayList<StumpRule>)
            adaboost(round);

            boolean overSized = cascade[round].size() > committeeSizeGuide;
            boolean finalTweak = overSized;

            int tweakCounter = 0;

            int[] oscillationObserver = new int[2];
            float tweak = 0;
            if (finalTweak)
                tweak = -1;
            float tweakUnit = TWEAK_UNIT;

            while (Math.abs(tweak) < 1.1) {
                tweaks.set(round, tweak);

                double[] resTrain = calcEmpiricalError(true, round);
                double[] resTest = calcEmpiricalError(false, round);

                double worstFalsePositive = Math.max(resTrain[0], resTest[0]);
                double worstDetectionRate = Math.min(resTrain[1], resTest[1]);

                if (finalTweak) {
                    if (worstDetectionRate >= 0.99) {
                        System.out.println("    - Final tweak settles to " + tweak);
                        break;
                    } else {
                        tweak += TWEAK_UNIT;
                        continue;
                    }
                }

                if (worstDetectionRate >= overallTargetDetectionRate && worstFalsePositive <= overallTargetFalsePositiveRate) {
                    layerMissionAccomplished = true;
                    System.out.println("    - worstDetectionRate: " + worstDetectionRate + " >= overallTargetDetectionRate: " + overallTargetDetectionRate + " && worstFalsePositive: " + worstFalsePositive + " <= overallTargetFalsePositiveRate: " + overallTargetFalsePositiveRate);
                    break;
                } else if (worstDetectionRate >= overallTargetDetectionRate && worstFalsePositive > overallTargetFalsePositiveRate) {
                    tweak -= tweakUnit;
                    tweakCounter++;
                    oscillationObserver[tweakCounter % 2] = -1;
                } else if (worstDetectionRate < overallTargetDetectionRate && worstFalsePositive <= overallTargetFalsePositiveRate) {
                    tweak += tweakUnit;
                    tweakCounter++;
                    oscillationObserver[tweakCounter % 2] = 1;
                } else {
                    finalTweak = true;
                    System.out.println("    - worstDetectionRate: " + worstDetectionRate + ">= overallTargetDetectionRate: " + overallTargetDetectionRate + " && worstFalsePositive: " + worstFalsePositive + "<= overallTargetFalsePositiveRate: " + overallTargetFalsePositiveRate);
                    System.out.println("    - No way out at this point. tweak goes from " + tweak);
                    continue;
                }

                // It is possible that tweak vacillates
                if (!finalTweak && tweakCounter > 1 && (oscillationObserver[0] + oscillationObserver[1]) == 0) {
                    // One solution is to reduce tweakUnit
                    tweakUnit /= 2;
                    tweak += oscillationObserver[tweakCounter % 2] == 1 ? -1 * tweakUnit : tweakUnit;

                    System.out.println("    - Backtracked at " + tweakCounter + "! Modify tweakUnit to " + tweakUnit);

                    if (tweakUnit < MIN_TWEAK) {
                        finalTweak = true;
                        System.out.println("    - TweakUnit too small. Tweak goes from " + tweak);
                    }
                }
            }
            if (overSized)
                break;
            nbWeakClassifier++;
        }
        return nbWeakClassifier;
    }

    private ArrayList<String> orderedExamples() {
        ArrayList<String> examples = new ArrayList<>(trainN);
        examples.addAll(trainFaces);
        examples.addAll(trainNonFaces);
        return examples;
    }

    public void train(String trainDir, String testDir, float initialPositiveWeight, float overallTargetDetectionRate, float overallTargetFalsePositiveRate, float targetFalsePositiveRate) {
        if (computed) {
            System.out.println("Training already done!");
            return;
        }
        long startTimeTrain = System.currentTimeMillis();

        train_dir = trainDir;
        trainFaces = listFiles(train_dir + "/faces", Conf.IMAGES_EXTENSION);
        trainNonFaces = listFiles(train_dir + "/non-faces", Conf.IMAGES_EXTENSION);
        countTrainPos = trainFaces.size();
        countTrainNeg = trainNonFaces.size();
        trainN = countTrainPos + countTrainNeg;

        System.out.println("Total number of training images: " + trainN + " (pos: " + countTrainPos + ", neg: " + countTrainNeg + ")");

        test_dir = testDir;
        testFaces = listFiles(test_dir + "/faces", Conf.IMAGES_EXTENSION);
        testNonFaces = listFiles(test_dir + "/non-faces", Conf.IMAGES_EXTENSION);
        countTestPos = testFaces.size();
        countTestNeg = testNonFaces.size();
        testN = countTestPos + countTestNeg;

        System.out.println("Total number of test images: " + testN + " (pos: " + countTestPos + ", neg: " + countTestNeg + ")");

        layerMemory = new ArrayList<>();

        // Compute all features for train & test set
        computeFeaturesTimed(train_dir);
        computeFeaturesTimed(test_dir);

        // Now organize all training features, so that it is easier to make requests on it
        organizeFeatures(featureCount, orderedExamples(), Conf.ORGANIZED_FEATURES, Conf.ORGANIZED_SAMPLE, false);

        System.out.println("Training classifier:");

        // Estimated number of rounds needed
        int boostingRounds = (int) (Math.ceil(Math.log(overallTargetFalsePositiveRate) / Math.log(targetFalsePositiveRate)) + 20);
        System.out.println("  - Estimated needed boosting rounds: " + boostingRounds);

        // Initialization
        tweaks = new ArrayList<>(boostingRounds);
        for (int i = 0; i < boostingRounds; i++)
            tweaks.add(0f);
        cascade = new ArrayList[boostingRounds];

        // Init labels
        labelsTrain = new DenseMatrix(1, trainN);
        labelsTest = new DenseMatrix(1, testN);
        for (int i = 0; i < trainN; i++) {
            labelsTrain.set(0, i, i < countTrainPos ? 1 : -1); // face == 1 VS non-face == -1
            labelsTrain.set(0, i, i < countTestPos ? 1 : -1); // face == 1 VS non-face == -1
        }

        double accumulatedFalsePositive = 1;

        // Training: run Cascade until we arrive to a certain wanted rate of success
        int round;
        for (round = 0; round < boostingRounds && accumulatedFalsePositive > GOAL; round++) {
            long startTimeFor = System.currentTimeMillis();
            System.out.println("  - Round N." + round + ":");

            // Update weights (needed because adaboost changes weights when running)
            totalWeightPos = initialPositiveWeight;
            totalWeightNeg = 1 - initialPositiveWeight;
            double averageWeightPos = totalWeightPos / countTrainPos;
            double averageWeightNeg = totalWeightNeg / countTrainNeg;
            minWeight = averageWeightPos < averageWeightNeg ? averageWeightPos : averageWeightNeg;
            maxWeight = averageWeightPos > averageWeightNeg ? averageWeightPos : averageWeightNeg;
            weightsTrain = DenseMatrix.zeros(1, trainN);
            for (int i = 0; i < trainN; i++)
                weightsTrain.set(0, i, i < countTrainPos ? averageWeightPos : averageWeightNeg);
            System.out.println("    - Initialized weights:");
            System.out.println("      - TotW+: " + totalWeightPos + " | TotW-: " + totalWeightNeg);
            System.out.println("      - AverW+: " + averageWeightPos + " | AverW-: " + averageWeightNeg);
            System.out.println("      - MinW: " + minWeight + " | MaxW: " + maxWeight);

            int nbWC = attentionalCascade(round, overallTargetDetectionRate, overallTargetFalsePositiveRate);
            System.out.println("    - Attentional Cascade computed in " + ((new Date()).getTime() - startTimeFor)/1000 + "s!");
            System.out.println("      - Number of weak classifier: " + nbWC);

            // -- Display results for this round --

            //layerMemory.add(trainSet.committee.size());
            layerMemory.add(cascade[round].size());
            System.out.println("    - The committee size is " + cascade[round].size());

            double[] tmp = calcEmpiricalError(true, round);
            System.out.println("    - The current tweak " + tweaks.get(round) + " has falsePositive " + tmp[0] + " and detectionRate " + tmp[1] + " on the training examples.");
            tmp = calcEmpiricalError(false, round);
            System.out.println("    - The current tweak " + tweaks.get(round) + " has falsePositive " + tmp[0] + " and detectionRate " + tmp[1] + " on the validation examples.");
            System.out.println("    - Target: falsePositiveTarget" + targetFalsePositiveRate + " detectionRateTarget " + overallTargetDetectionRate);
            accumulatedFalsePositive *= tmp[0];
            System.out.println("    - Accumulated False Positive Rate is around " + accumulatedFalsePositive);

            //record the boosted rule into a target file
            Serializer.writeRule(cascade[round], round == 0, Conf.TRAIN_FEATURES);

        }

        // Serialize training
        Serializer.writeLayerMemory(this.layerMemory, this.tweaks, Conf.TRAIN_FEATURES);

        computed = true;

        System.out.println("Training done in " + ((new Date()).getTime() - startTimeTrain)/1000 + "s!");
        System.out.println("  - Cascade of " + (round - 1) + " rounds");
        System.out.println("  - Weak classifiers count by round:");
        for (int i = 0; i < round-1; i++)
            System.out.println("    - Round " + i + ": " + cascade[i].size());
    }

    public float test(String dir) {
        /*if (!computed) {
            System.err.println("Train the classifier before testing it!");
            System.exit(1);
        }*/

        test_dir = dir;
        countTestPos = countFiles(test_dir + "/faces", Conf.IMAGES_EXTENSION);
        countTestNeg = countFiles(test_dir + "/non-faces", Conf.IMAGES_EXTENSION);
        testN = countTestPos + countTestNeg;

        computeFeaturesTimed(test_dir);

        ArrayList<StumpRule> rules = Serializer.readRule(Conf.TRAIN_FEATURES);

        long goodFaces = 0;
        long wrongFaces = 0;
        long goodNonFaces = 0;
        long wrongNonFaces = 0;

        for (String listTestFace : streamFiles(test_dir + "/faces", Conf.FEATURE_EXTENSION)) {
            double sum = 0;

            ArrayList<Integer> haar = readArrayFromDisk(listTestFace);

            for (StumpRule rule : rules) {
                double alpha = 0.5d * log((1.0d - rule.error) / rule.error);
                double val = haar.get((int) rule.featureIndex);
                sum += rule.toggle * alpha * (val < rule.threshold ? -1 : 1);
            }

            if (sum < 0)
                goodFaces++;
            else
                wrongFaces++;
        }

        for (String listTestNonFace : streamFiles(test_dir + "/non-faces", Conf.FEATURE_EXTENSION)) {
            double sum = 0;

            ArrayList<Integer> haar = readArrayFromDisk(listTestNonFace);

            for (StumpRule rule : rules) {
                double alpha = 0.5d * log((1.0d - rule.error) / rule.error);
                double val = haar.get((int) rule.featureIndex);
                sum += rule.toggle * alpha * (val < rule.threshold ? -1 : 1);
            }

            if (sum < 0)
                goodNonFaces++;
            else
                wrongNonFaces++;
        }

        System.out.println("Vrai Positifs : " + goodFaces + " / " + countTestPos + " (" + (((double)goodFaces)/(double)countTestPos + "%)"));
        System.out.println("Vrai Negatifs : " + wrongFaces + " / " + countTestPos + " (" + (((double)wrongFaces)/(double)countTestPos + "%)"));
        System.out.println("Faux Positifs : " + goodNonFaces + " / " + countTestNeg + " (" + (((double)goodNonFaces)/(double)countTestNeg + "%)"));
        System.out.println("Faux Negatifs : " + wrongNonFaces + " / " + countTestNeg + " (" + (((double)wrongNonFaces)/(double)countTestNeg + "%)"));

        System.out.println("Positifs : " + (goodFaces + goodNonFaces) + " / " + (countTestPos + countTestNeg) + " (" + (((double)(goodFaces+goodNonFaces))/(double)(countTestPos+countTestNeg)+ "%)"));
        System.out.println("Negatifs : " + (wrongFaces + wrongNonFaces) + " / " + (countTestPos + countTestNeg) + " (" + (((double)(wrongFaces+wrongNonFaces))/(double)(countTestPos+countTestNeg)+ "%)"));

        System.out.println("Taux de detection : " + (((((double)goodFaces)/(double)countTestPos) + (((double)goodNonFaces)/(double)countTestNeg))/2.0d) + "%");
        System.out.println("Taux de detection : " + ((1.0d - (double)goodNonFaces)/(double)countTestPos) + "%");

        // TODO: after the training has been done, we can test on a new set of images.

        return 0;
    }
}

