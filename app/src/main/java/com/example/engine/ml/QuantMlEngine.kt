package com.example.engine.ml

import com.example.data.model.Candle
import com.example.data.model.DataOrigin
import com.example.data.model.DataQualityThresholds
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.MlClassMetrics
import com.example.data.model.MlMovementClass
import com.example.data.model.MlPrediction
import com.example.data.model.MlValidationReport
import com.example.data.model.VerificationStatus
import com.example.engine.indicators.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

data class FeatureVector(
    val return5: Double,
    val return10: Double,
    val rsi14Norm: Double,
    val macdHistNorm: Double,
    val bbPercentB: Double,
    val atrRatio: Double,
    val volumeRatio: Double,
    val ema9_21Ratio: Double
) {
    fun toArray(): DoubleArray = doubleArrayOf(
        return5, return10, rsi14Norm, macdHistNorm, bbPercentB, atrRatio, volumeRatio, ema9_21Ratio
    )

    companion object {
        val FEATURE_NAMES = listOf(
            "Return_5", "Return_10", "RSI_Norm", "MACD_Hist_Norm",
            "Bollinger_%B", "ATR_Ratio", "Volume_Ratio", "EMA_Cross"
        )
    }
}

data class LabeledSample(
    val candleIndex: Int,
    val timestamp: Long,
    val features: DoubleArray,
    val label: MlMovementClass
)

/**
 * Serialized CART Decision Tree Node for Random Forest.
 * Kept as a single concrete class (no polymorphism) for reliable Moshi JSON persistence.
 */
data class DecisionTreeNode(
    val isLeaf: Boolean,
    val featureIndex: Int = -1,
    val threshold: Double = 0.0,
    val left: DecisionTreeNode? = null,
    val right: DecisionTreeNode? = null,
    val upCount: Int = 0,
    val downCount: Int = 0,
    val sidewaysCount: Int = 0
) {
    fun predictProbs(features: DoubleArray, laplaceAlpha: Double = 1.0): DoubleArray {
        if (isLeaf) {
            val total = upCount + downCount + sidewaysCount
            val denom = total + 3.0 * laplaceAlpha
            return doubleArrayOf(
                (upCount + laplaceAlpha) / denom,
                (downCount + laplaceAlpha) / denom,
                (sidewaysCount + laplaceAlpha) / denom
            )
        }
        val next = if (features[featureIndex] <= threshold) left else right
        return next?.predictProbs(features, laplaceAlpha) ?: doubleArrayOf(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)
    }
}

data class PersistedRandomForestModel(
    val modelVersion: String,
    val trees: List<DecisionTreeNode>,
    val featureImportances: Map<String, Double>,
    val validationReport: MlValidationReport
)

data class MlTrainingOutcome(
    val report: MlValidationReport,
    val deployed: Boolean
)

/**
 * Genuine Random Forest Classification Engine for Financial Time-Series.
 *
 * Architecture:
 * - Causal 8-feature extraction (zero look-ahead)
 * - Purged chronological Train / Validation / Test split (gap = forwardHorizon)
 * - Bootstrap aggregation (Bagging) across T CART decision trees
 * - Random feature subsampling (m = sqrt(d) = 3 features per split)
 * - Exact Gini impurity minimization
 * - Laplace-smoothed empirical leaf probability averaging (no uncalibrated softmax)
 * - Walk-forward cross-validation & majority/momentum baseline comparisons
 */
class QuantMlEngine(
    val modelVersion: String = "QuantAI-RF-v2.0"
) {
    companion object {
        const val FEATURE_VERSION = "features-v1.0"
    }

    private val lock = Any()

    @Volatile
    private var forestTrees: List<DecisionTreeNode> = emptyList()

    @Volatile
    private var featureImportances: Map<String, Double> = emptyMap()

    @Volatile
    private var lastReport: MlValidationReport? = null

    val isTrained: Boolean
        get() = forestTrees.isNotEmpty() && lastReport != null

    fun getValidationReport(): MlValidationReport? = lastReport

    fun getFeatureImportances(): Map<String, Double> = featureImportances

    fun invalidateModel() {
        synchronized(lock) {
            forestTrees = emptyList()
            featureImportances = emptyMap()
            lastReport = null
        }
    }

    /**
     * Enforces strict binding across symbol + timeframe + datasetId/checksum + featureVersion + modelVersion.
     * Prevents stale or cross-dataset/cross-symbol/cross-timeframe predictions after dataset replacement or deletion.
     */
    fun isModelValidFor(
        symbol: String,
        timeframe: String,
        datasetMeta: HistoricalDatasetMetadata? = null
    ): Boolean {
        val rep = lastReport ?: return false
        if (forestTrees.isEmpty()) return false
        if (rep.modelVersion != modelVersion) return false
        if (rep.featureVersion != FEATURE_VERSION) return false

        val symMatch = rep.trainedSymbol == symbol || (rep.trainedSymbol == "UNKNOWN" && datasetMeta == null)
        if (!symMatch) return false
        if (rep.timeframe != timeframe) return false

        return if (datasetMeta != null) {
            rep.datasetId == datasetMeta.datasetId &&
                (datasetMeta.sha256Checksum.isBlank() || rep.datasetChecksum == datasetMeta.sha256Checksum)
        } else {
            rep.datasetId.isEmpty() && rep.sourceType == DataOrigin.SIMULATED
        }
    }

    /**
     * Extracts causal feature vector at candle index `atIndex` using ONLY candles[0..atIndex].
     */
    fun extractFeatures(candles: List<Candle>, atIndex: Int = candles.size - 1): FeatureVector? {
        if (atIndex < 25 || candles.size <= atIndex) return null
        val sub = candles.take(atIndex + 1)
        val currentClose = sub.last().close
        if (currentClose <= 0) return null

        val close5Ago = sub[sub.size - 6].close
        val return5 = if (close5Ago > 0) (currentClose - close5Ago) / close5Ago else 0.0

        val close10Ago = sub[sub.size - 11].close
        val return10 = if (close10Ago > 0) (currentClose - close10Ago) / close10Ago else 0.0

        val closes = sub.map { it.close }
        val rsi14 = TechnicalIndicators.calculateRSI(sub, 14)
        val macd = TechnicalIndicators.calculateMACD(sub)
        val bb = TechnicalIndicators.calculateBollingerBands(sub, 20, 2.0)
        val atr14 = TechnicalIndicators.calculateATR(sub, 14)
        val ema9 = TechnicalIndicators.calculateEMA(closes, 9)
        val ema21 = TechnicalIndicators.calculateEMA(closes, 21)

        val avgVolume20 = sub.takeLast(20).map { it.volume }.average()
        val volumeRatio = if (avgVolume20 > 0) (sub.last().volume / avgVolume20).coerceIn(0.2, 5.0) else 1.0

        return FeatureVector(
            return5 = return5,
            return10 = return10,
            rsi14Norm = (rsi14 - 50.0) / 50.0,
            macdHistNorm = macd.histogram / currentClose,
            bbPercentB = bb.percentB,
            atrRatio = atr14 / currentClose,
            volumeRatio = volumeRatio,
            ema9_21Ratio = (ema9 - ema21) / currentClose
        )
    }

    /**
     * Fast O(N) strictly causal dataset generation over all valid indices [25 .. N - forwardHorizon - 1].
     * Each sample i uses ONLY candles[0..i] for features, and candles[i + forwardHorizon] ONLY for target label.
     */
    fun generateDataset(
        candles: List<Candle>,
        forwardHorizon: Int = 5,
        costFriction: Double = 0.0015,
        returnThreshold: Double = 0.0020
    ): List<LabeledSample> {
        val n = candles.size
        if (n < 35 + forwardHorizon) return emptyList()

        val closes = DoubleArray(n) { candles[it].close }
        val highs = DoubleArray(n) { candles[it].high }
        val lows = DoubleArray(n) { candles[it].low }
        val volumes = LongArray(n) { candles[it].volume }

        // Precompute strictly causal EMA9, EMA21, EMA12, EMA26, MACD Signal(9), RSI(14), ATR(14)
        val ema9 = DoubleArray(n)
        val ema21 = DoubleArray(n)
        val ema12 = DoubleArray(n)
        val ema26 = DoubleArray(n)
        val macdHist = DoubleArray(n)
        val rsi = DoubleArray(n) { 50.0 }
        val atr = DoubleArray(n)

        // Causal EMAs
        computeCausalEma(closes, 9, ema9)
        computeCausalEma(closes, 21, ema21)
        computeCausalEma(closes, 12, ema12)
        computeCausalEma(closes, 26, ema26)

        // Causal MACD Signal (EMA9 of MACD line starting at index 25, i.e. 26th bar)
        val slowPeriod = 26
        val signalPeriod = 9
        val macdLine = DoubleArray(n)
        for (i in (slowPeriod - 1) until n) {
            macdLine[i] = ema12[i] - ema26[i]
        }
        if (n >= slowPeriod + signalPeriod) {
            var sigSum = 0.0
            for (i in (slowPeriod - 1) until (slowPeriod - 1 + signalPeriod)) {
                sigSum += macdLine[i]
            }
            var sigEma = sigSum / signalPeriod
            val sigMult = 2.0 / (signalPeriod + 1.0)
            val firstSigIdx = slowPeriod - 1 + signalPeriod - 1
            macdHist[firstSigIdx] = macdLine[firstSigIdx] - sigEma
            for (i in (firstSigIdx + 1) until n) {
                sigEma = (macdLine[i] - sigEma) * sigMult + sigEma
                macdHist[i] = macdLine[i] - sigEma
            }
        }

        // Causal Wilder RSI(14)
        val rsiPeriod = 14
        if (n > rsiPeriod) {
            var avgGain = 0.0
            var avgLoss = 0.0
            for (i in 1..rsiPeriod) {
                val chg = closes[i] - closes[i - 1]
                if (chg >= 0) avgGain += chg else avgLoss += abs(chg)
            }
            avgGain /= rsiPeriod
            avgLoss /= rsiPeriod
            rsi[rsiPeriod] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
            for (i in (rsiPeriod + 1) until n) {
                val chg = closes[i] - closes[i - 1]
                val gain = if (chg > 0) chg else 0.0
                val loss = if (chg < 0) abs(chg) else 0.0
                avgGain = (avgGain * (rsiPeriod - 1) + gain) / rsiPeriod
                avgLoss = (avgLoss * (rsiPeriod - 1) + loss) / rsiPeriod
                rsi[i] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
            }
        }

        // Causal Wilder ATR(14)
        val atrPeriod = 14
        if (n > atrPeriod) {
            val tr = DoubleArray(n)
            for (i in 1 until n) {
                val prevClose = closes[i - 1]
                tr[i] = max(highs[i] - lows[i], max(abs(highs[i] - prevClose), abs(lows[i] - prevClose)))
            }
            var trSum = 0.0
            for (i in 1..atrPeriod) trSum += tr[i]
            var curAtr = trSum / atrPeriod
            atr[atrPeriod] = curAtr
            for (i in (atrPeriod + 1) until n) {
                curAtr = (curAtr * (atrPeriod - 1) + tr[i]) / atrPeriod
                atr[i] = curAtr
            }
        }

        val hurdle = costFriction + returnThreshold
        val samples = ArrayList<LabeledSample>(n - 25 - forwardHorizon)

        for (i in 25 until (n - forwardHorizon)) {
            val c = closes[i]
            if (c <= 0.0) continue

            val return5 = if (closes[i - 5] > 0) (c - closes[i - 5]) / closes[i - 5] else 0.0
            val return10 = if (closes[i - 10] > 0) (c - closes[i - 10]) / closes[i - 10] else 0.0

            // Causal Bollinger %B (period = 20)
            var sumClose = 0.0
            for (k in (i - 19)..i) sumClose += closes[k]
            val middle = sumClose / 20.0
            var varSum = 0.0
            for (k in (i - 19)..i) {
                val d = closes[k] - middle
                varSum += d * d
            }
            val stdDev = sqrt(varSum / 20.0)
            val upper = middle + 2.0 * stdDev
            val lower = middle - 2.0 * stdDev
            val bandDiff = upper - lower
            val bbPercentB = if (bandDiff > 0.0) (c - lower) / bandDiff else 0.5

            // Causal 20-bar average volume
            var volSum = 0.0
            for (k in (i - 19)..i) volSum += volumes[k].toDouble()
            val avgVol20 = volSum / 20.0
            val volRatio = if (avgVol20 > 0.0) (volumes[i].toDouble() / avgVol20).coerceIn(0.2, 5.0) else 1.0

            val featArray = doubleArrayOf(
                return5,
                return10,
                (rsi[i] - 50.0) / 50.0,
                macdHist[i] / c,
                bbPercentB,
                atr[i] / c,
                volRatio,
                (ema9[i] - ema21[i]) / c
            )

            val futureClose = closes[i + forwardHorizon]
            val forwardReturn = (futureClose - c) / c

            val label = when {
                forwardReturn > hurdle -> MlMovementClass.UPWARD
                forwardReturn < -hurdle -> MlMovementClass.DOWNWARD
                else -> MlMovementClass.SIDEWAYS
            }

            samples.add(
                LabeledSample(
                    candleIndex = i,
                    timestamp = candles[i].timestamp,
                    features = featArray,
                    label = label
                )
            )
        }

        return samples
    }

    private fun computeCausalEma(values: DoubleArray, period: Int, out: DoubleArray) {
        if (values.size < period) return
        var sum = 0.0
        for (i in 0 until period) sum += values[i]
        var ema = sum / period
        out[period - 1] = ema
        val mult = 2.0 / (period + 1.0)
        for (i in period until values.size) {
            ema = (values[i] - ema) * mult + ema
            out[i] = ema
        }
    }

    /**
     * Trains a genuine Random Forest with Purged Chronological Split (60% Train / 20% Val / 20% Test)
     * and Walk-Forward Validation. Evaluates unseen test metrics & baseline comparisons.
     *
     * Validation Gate: Only deploys the model if it passes non-collapse and sample sufficiency checks.
     */
    fun trainAndValidate(
        candles: List<Candle>,
        symbol: String = "UNKNOWN",
        timeframe: String = "1m",
        forwardHorizon: Int = 5,
        costFriction: Double = 0.0015,
        returnThreshold: Double = 0.0020,
        numTrees: Int = 35,
        maxDepth: Int = 5,
        minSamplesSplit: Int = 8,
        minLeafSamples: Int = 3,
        seed: Long = 42L,
        datasetMeta: HistoricalDatasetMetadata? = null,
        researchMinBarsOverrides: Map<String, Int> = DataQualityThresholds.defaultResearchMinBars
    ): MlTrainingOutcome? {
        val dataset = generateDataset(
            candles = candles,
            forwardHorizon = forwardHorizon,
            costFriction = costFriction,
            returnThreshold = returnThreshold
        )
        val n = dataset.size
        val purgeGap = forwardHorizon
        // Need enough samples for 60/20/20 split with 2 purge gaps
        if (n < 40 + 2 * purgeGap) return null

        val resolvedOrigin = datasetMeta?.sourceType ?: if (candles.any { it.feedStatus == FeedStatus.SIMULATED }) {
            DataOrigin.SIMULATED
        } else {
            DataOrigin.IMPORTED_CSV
        }
        val resolvedVerification = datasetMeta?.verificationStatus ?: when (resolvedOrigin) {
            DataOrigin.SIMULATED -> VerificationStatus.SIMULATED_NOT_REAL
            DataOrigin.TEST_FIXTURE -> VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA
            DataOrigin.IMPORTED_CSV -> VerificationStatus.IMPORTED_CSV_UNVERIFIED
            DataOrigin.BROKER_VENDOR -> VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR
        }
        val isSynthetic = candles.any { it.feedStatus == FeedStatus.SIMULATED } ||
            resolvedOrigin == DataOrigin.SIMULATED ||
            resolvedOrigin == DataOrigin.TEST_FIXTURE

        val trainEnd = (n * 0.60).toInt()
        val valStart = (trainEnd + purgeGap).coerceAtMost(n)
        val valCount = (n * 0.20).toInt().coerceAtLeast(1)
        val valEnd = (valStart + valCount).coerceAtMost(n)
        val testStart = (valEnd + purgeGap).coerceAtMost(n)

        if (trainEnd < 20 || testStart >= n) return null

        val trainSet = dataset.subList(0, trainEnd)
        val valSet = dataset.subList(valStart, valEnd)
        val testSet = dataset.subList(testStart, n)
        val purgedSamples = (valStart - trainEnd) + (testStart - valEnd)

        // Train Random Forest strictly on trainSet
        val trained = fitRandomForest(
            trainSet = trainSet,
            numTrees = numTrees,
            maxDepth = maxDepth,
            minSamplesSplit = minSamplesSplit,
            minLeafSamples = minLeafSamples,
            seed = seed
        )

        // Evaluate strictly on unseen chronological testSet
        val eval = evaluateForestOnSet(trained.trees, testSet, trainSet)

        // Walk-Forward Validation across expanding chronological folds (purged)
        val (wfAcc, wfFolds) = runWalkForwardEvaluation(
            dataset = dataset,
            purgeGap = purgeGap,
            numTrees = minOf(numTrees, 25),
            maxDepth = maxDepth,
            minSamplesSplit = minSamplesSplit,
            minLeafSamples = minLeafSamples,
            seed = seed
        )

        val trainCounts = countClasses(trainSet)
        val testCounts = countClasses(testSet)

        // Validation Gate: reject degenerate/collapsed or insufficient models
        val distinctPredictedClasses = eval.confusionMatrix.indices.count { col ->
            eval.confusionMatrix.sumOf { row -> row[col] } > 0
        }

        val gateReasons = mutableListOf<String>()
        if (testSet.size < 15) gateReasons.add("Test set < 15 samples (${testSet.size})")
        if (eval.macroF1 < 0.20) gateReasons.add("Macro F1 ${String.format("%.2f", eval.macroF1)} < 0.20")
        if (eval.logLoss > 1.35) gateReasons.add("LogLoss ${String.format("%.2f", eval.logLoss)} > 1.35 (miscalibrated)")
        if (distinctPredictedClasses < 2 && testCounts.values.count { it > 0 } > 1) {
            gateReasons.add("Model collapsed to single class")
        }

        val passedGate = gateReasons.isEmpty()
        val gateReason = if (passedGate) {
            "Passed purged OOS validation (${testSet.size} test samples, ${purgedSamples} purged)"
        } else {
            "Rejected: ${gateReasons.joinToString("; ")}"
        }

        val reqResearchBars = DataQualityThresholds.requiredResearchBars(timeframe, researchMinBarsOverrides)
        val researchStatus = DataQualityThresholds.evaluateQuality(candles.size, timeframe, researchMinBarsOverrides)

        val report = MlValidationReport(
            modelVersion = modelVersion,
            trainedSymbol = symbol,
            timeframe = timeframe,
            trainedAt = System.currentTimeMillis(),
            isSyntheticData = isSynthetic,
            forwardHorizon = forwardHorizon,
            costFriction = costFriction,
            totalSamples = n,
            trainSamples = trainSet.size,
            validationSamples = valSet.size,
            testSamples = testSet.size,
            purgedSamples = purgedSamples,
            trainClassCounts = trainCounts,
            testClassCounts = testCounts,
            testAccuracy = eval.accuracy,
            baselineMajorityAccuracy = eval.majorityBaselineAccuracy,
            baselineMomentumAccuracy = eval.momentumBaselineAccuracy,
            macroF1 = eval.macroF1,
            testLogLoss = eval.logLoss,
            testBrierScore = eval.brierScore,
            walkForwardAccuracy = wfAcc,
            walkForwardFolds = wfFolds,
            classMetrics = eval.classMetrics,
            confusionMatrix = eval.confusionMatrix,
            featureImportances = trained.featureImportances,
            passedValidationGate = passedGate,
            validationGateReason = gateReason,
            testStartCandleIndex = testSet.first().candleIndex,
            datasetId = datasetMeta?.datasetId ?: "",
            datasetChecksum = datasetMeta?.sha256Checksum ?: "",
            featureVersion = FEATURE_VERSION,
            sourceType = resolvedOrigin,
            verificationStatus = resolvedVerification,
            researchQualityStatus = researchStatus,
            researchMinRequiredBars = reqResearchBars
        )

        if (passedGate) {
            synchronized(lock) {
                forestTrees = trained.trees
                featureImportances = trained.featureImportances
                lastReport = report
            }
        }

        return MlTrainingOutcome(report = report, deployed = passedGate)
    }

    /**
     * Loads a previously validated and persisted model from disk without retraining.
     */
    fun loadPersistedModel(persisted: PersistedRandomForestModel) {
        synchronized(lock) {
            forestTrees = persisted.trees
            featureImportances = persisted.featureImportances
            lastReport = persisted.validationReport
        }
    }

    fun exportPersistedModel(): PersistedRandomForestModel? {
        synchronized(lock) {
            val rep = lastReport ?: return null
            if (forestTrees.isEmpty()) return null
            return PersistedRandomForestModel(
                modelVersion = modelVersion,
                trees = forestTrees,
                featureImportances = featureImportances,
                validationReport = rep
            )
        }
    }

    /**
     * Context-bound inference enforcing symbol + timeframe + datasetId/checksum + featureVersion + modelVersion.
     * Returns null when < 26 candles or an explicit untrained placeholder when binding does not match.
     */
    fun predictForContext(
        candles: List<Candle>,
        symbol: String,
        timeframe: String,
        datasetMeta: HistoricalDatasetMetadata? = null
    ): MlPrediction? {
        if (!isModelValidFor(symbol, timeframe, datasetMeta)) {
            if (candles.size < 26) return null
            return untrainedPlaceholder()
        }
        return predict(candles)
    }

    /**
     * Inference on latest candle in stream. Returns null if fewer than 26 candles.
     * If model is not trained, returns an explicit untrained placeholder (never fake probabilities).
     */
    fun predict(candles: List<Candle>): MlPrediction? {
        val features = extractFeatures(candles) ?: return null
        return predictFromFeatures(features.toArray())
    }

    private fun untrainedPlaceholder(): MlPrediction = MlPrediction(
        modelVersion = modelVersion,
        isTrained = false,
        isSyntheticData = true,
        probUpward = 1.0 / 3.0,
        probDownward = 1.0 / 3.0,
        probSideways = 1.0 / 3.0,
        predictedClass = MlMovementClass.SIDEWAYS,
        confidence = 1.0 / 3.0,
        trainingSampleCount = 0,
        outOfSampleAccuracy = 0.0,
        featureImportances = emptyMap(),
        evaluatedAt = System.currentTimeMillis(),
        datasetId = "",
        featureVersion = FEATURE_VERSION,
        sourceType = DataOrigin.SIMULATED,
        verificationStatus = VerificationStatus.SIMULATED_NOT_REAL
    )

    fun predictFromFeatures(featArray: DoubleArray): MlPrediction {
        val trees = forestTrees
        val report = lastReport
        val importances = featureImportances

        if (trees.isEmpty() || report == null ||
            report.modelVersion != modelVersion ||
            report.featureVersion != FEATURE_VERSION
        ) {
            return untrainedPlaceholder()
        }

        val probs = predictForestProbabilities(trees, featArray)
        val probUp = probs[0]
        val probDown = probs[1]
        val probSideways = probs[2]

        val predictedClass = when {
            probUp >= probDown && probUp >= probSideways -> MlMovementClass.UPWARD
            probDown >= probUp && probDown >= probSideways -> MlMovementClass.DOWNWARD
            else -> MlMovementClass.SIDEWAYS
        }

        val confidence = max(probUp, max(probDown, probSideways))

        return MlPrediction(
            modelVersion = modelVersion,
            isTrained = true,
            isSyntheticData = report.isSyntheticData,
            probUpward = probUp,
            probDownward = probDown,
            probSideways = probSideways,
            predictedClass = predictedClass,
            confidence = confidence,
            trainingSampleCount = report.trainSamples,
            outOfSampleAccuracy = report.testAccuracy,
            featureImportances = importances,
            evaluatedAt = System.currentTimeMillis(),
            datasetId = report.datasetId,
            featureVersion = report.featureVersion,
            sourceType = report.sourceType,
            verificationStatus = report.verificationStatus
        )
    }

    private data class FittedForest(
        val trees: List<DecisionTreeNode>,
        val featureImportances: Map<String, Double>
    )

    private fun fitRandomForest(
        trainSet: List<LabeledSample>,
        numTrees: Int,
        maxDepth: Int,
        minSamplesSplit: Int,
        minLeafSamples: Int,
        seed: Long
    ): FittedForest {
        val numFeatures = FeatureVector.FEATURE_NAMES.size
        val featuresPerSplit = max(1, sqrt(numFeatures.toDouble()).toInt()) // sqrt(8) = 2 or 3; use 3
        val mTry = max(3, featuresPerSplit)

        val importanceAccum = DoubleArray(numFeatures)
        val trees = ArrayList<DecisionTreeNode>(numTrees)

        for (t in 0 until numTrees) {
            val rng = Random(seed + t * 997L)
            // Bootstrap sample (size N with replacement)
            val bootstrap = List(trainSet.size) {
                trainSet[rng.nextInt(trainSet.size)]
            }
            val tree = buildCartTree(
                samples = bootstrap,
                depth = 0,
                maxDepth = maxDepth,
                minSamplesSplit = minSamplesSplit,
                minLeafSamples = minLeafSamples,
                mTry = mTry,
                numFeatures = numFeatures,
                rng = rng,
                importanceAccum = importanceAccum
            )
            trees.add(tree)
        }

        val totalImp = importanceAccum.sum()
        val normalizedImportances = LinkedHashMap<String, Double>()
        for (idx in FeatureVector.FEATURE_NAMES.indices) {
            val name = FeatureVector.FEATURE_NAMES[idx]
            normalizedImportances[name] = if (totalImp > 0.0) importanceAccum[idx] / totalImp else (1.0 / numFeatures)
        }

        return FittedForest(trees = trees, featureImportances = normalizedImportances)
    }

    private fun buildCartTree(
        samples: List<LabeledSample>,
        depth: Int,
        maxDepth: Int,
        minSamplesSplit: Int,
        minLeafSamples: Int,
        mTry: Int,
        numFeatures: Int,
        rng: Random,
        importanceAccum: DoubleArray
    ): DecisionTreeNode {
        var upCount = 0
        var downCount = 0
        var sideCount = 0
        for (s in samples) {
            when (s.label) {
                MlMovementClass.UPWARD -> upCount++
                MlMovementClass.DOWNWARD -> downCount++
                MlMovementClass.SIDEWAYS -> sideCount++
            }
        }

        val n = samples.size
        val parentGini = giniImpurity(upCount, downCount, sideCount, n)

        if (depth >= maxDepth || n < minSamplesSplit || parentGini <= 1e-9) {
            return DecisionTreeNode(
                isLeaf = true,
                upCount = upCount,
                downCount = downCount,
                sidewaysCount = sideCount
            )
        }

        // Random feature subsampling without replacement
        val candidateFeatures = (0 until numFeatures).shuffled(rng).take(mTry)

        var bestGain = 0.0
        var bestFeature = -1
        var bestThreshold = 0.0

        for (featIdx in candidateFeatures) {
            val values = samples.map { it.features[featIdx] }.distinct().sorted()
            if (values.size < 2) continue

            // Up to 16 evenly spaced midpoint thresholds for speed & anti-overfitting
            val step = max(1, (values.size - 1) / 16)
            for (k in 0 until (values.size - 1) step step) {
                val threshold = 0.5 * (values[k] + values[k + 1])

                var lUp = 0; var lDown = 0; var lSide = 0; var lN = 0
                var rUp = 0; var rDown = 0; var rSide = 0; var rN = 0

                for (s in samples) {
                    if (s.features[featIdx] <= threshold) {
                        lN++
                        when (s.label) {
                            MlMovementClass.UPWARD -> lUp++
                            MlMovementClass.DOWNWARD -> lDown++
                            MlMovementClass.SIDEWAYS -> lSide++
                        }
                    } else {
                        rN++
                        when (s.label) {
                            MlMovementClass.UPWARD -> rUp++
                            MlMovementClass.DOWNWARD -> rDown++
                            MlMovementClass.SIDEWAYS -> rSide++
                        }
                    }
                }

                if (lN < minLeafSamples || rN < minLeafSamples) continue

                val gLeft = giniImpurity(lUp, lDown, lSide, lN)
                val gRight = giniImpurity(rUp, rDown, rSide, rN)
                val weightedGini = (lN.toDouble() / n) * gLeft + (rN.toDouble() / n) * gRight
                val gain = parentGini - weightedGini

                if (gain > bestGain) {
                    bestGain = gain
                    bestFeature = featIdx
                    bestThreshold = threshold
                }
            }
        }

        if (bestFeature == -1 || bestGain <= 1e-9) {
            return DecisionTreeNode(
                isLeaf = true,
                upCount = upCount,
                downCount = downCount,
                sidewaysCount = sideCount
            )
        }

        importanceAccum[bestFeature] += n.toDouble() * bestGain

        val leftSamples = samples.filter { it.features[bestFeature] <= bestThreshold }
        val rightSamples = samples.filter { it.features[bestFeature] > bestThreshold }

        return DecisionTreeNode(
            isLeaf = false,
            featureIndex = bestFeature,
            threshold = bestThreshold,
            left = buildCartTree(leftSamples, depth + 1, maxDepth, minSamplesSplit, minLeafSamples, mTry, numFeatures, rng, importanceAccum),
            right = buildCartTree(rightSamples, depth + 1, maxDepth, minSamplesSplit, minLeafSamples, mTry, numFeatures, rng, importanceAccum),
            upCount = upCount,
            downCount = downCount,
            sidewaysCount = sideCount
        )
    }

    private fun giniImpurity(up: Int, down: Int, side: Int, total: Int): Double {
        if (total <= 0) return 0.0
        val pUp = up.toDouble() / total
        val pDown = down.toDouble() / total
        val pSide = side.toDouble() / total
        return 1.0 - (pUp * pUp + pDown * pDown + pSide * pSide)
    }

    private fun predictForestProbabilities(trees: List<DecisionTreeNode>, features: DoubleArray): DoubleArray {
        var sumUp = 0.0
        var sumDown = 0.0
        var sumSide = 0.0

        for (tree in trees) {
            val p = tree.predictProbs(features, laplaceAlpha = 1.0)
            sumUp += p[0]
            sumDown += p[1]
            sumSide += p[2]
        }
        val t = trees.size.toDouble()
        val pUp = sumUp / t
        val pDown = sumDown / t
        val pSide = sumSide / t
        val norm = pUp + pDown + pSide
        return if (norm > 0.0) doubleArrayOf(pUp / norm, pDown / norm, pSide / norm)
        else doubleArrayOf(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)
    }

    private data class EvaluationSummary(
        val accuracy: Double,
        val majorityBaselineAccuracy: Double,
        val momentumBaselineAccuracy: Double,
        val macroF1: Double,
        val logLoss: Double,
        val brierScore: Double,
        val classMetrics: List<MlClassMetrics>,
        val confusionMatrix: List<List<Int>>
    )

    private fun evaluateForestOnSet(
        trees: List<DecisionTreeNode>,
        testSet: List<LabeledSample>,
        trainSet: List<LabeledSample>
    ): EvaluationSummary {
        val classes = listOf(MlMovementClass.UPWARD, MlMovementClass.DOWNWARD, MlMovementClass.SIDEWAYS)
        val matrix = Array(3) { IntArray(3) }

        val trainMajorityClass = countClasses(trainSet).maxByOrNull { it.value }?.key ?: MlMovementClass.SIDEWAYS

        var correct = 0
        var majCorrect = 0
        var momCorrect = 0
        var logLossSum = 0.0
        var brierSum = 0.0

        for (sample in testSet) {
            val probs = predictForestProbabilities(trees, sample.features)
            val predIdx = when {
                probs[0] >= probs[1] && probs[0] >= probs[2] -> 0
                probs[1] >= probs[0] && probs[1] >= probs[2] -> 1
                else -> 2
            }
            val actualIdx = classToIndex(sample.label)
            matrix[actualIdx][predIdx]++
            if (predIdx == actualIdx) correct++

            if (trainMajorityClass == sample.label) majCorrect++

            // Simple 5-period momentum baseline
            val ret5 = sample.features[0]
            val momPred = when {
                ret5 > 0.002 -> MlMovementClass.UPWARD
                ret5 < -0.002 -> MlMovementClass.DOWNWARD
                else -> MlMovementClass.SIDEWAYS
            }
            if (momPred == sample.label) momCorrect++

            // Calibration losses
            val pActual = probs[actualIdx].coerceIn(1e-7, 1.0 - 1e-7)
            logLossSum += -ln(pActual)

            for (c in 0 until 3) {
                val target = if (c == actualIdx) 1.0 else 0.0
                val diff = probs[c] - target
                brierSum += diff * diff
            }
        }

        val n = testSet.size.coerceAtLeast(1)
        val classMetrics = classes.mapIndexed { idx, cls ->
            val tp = matrix[idx][idx]
            val fp = (0 until 3).sumOf { r -> if (r != idx) matrix[r][idx] else 0 }
            val fn = (0 until 3).sumOf { c -> if (c != idx) matrix[idx][c] else 0 }
            val support = matrix[idx].sum()
            val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp) else 0.0
            val recall = if (tp + fn > 0) tp.toDouble() / (tp + fn) else 0.0
            val f1 = if (precision + recall > 0) (2.0 * precision * recall) / (precision + recall) else 0.0
            MlClassMetrics(cls, support, precision, recall, f1)
        }

        val presentClasses = classMetrics.filter { it.support > 0 }
        val macroF1 = if (presentClasses.isNotEmpty()) presentClasses.map { it.f1Score }.average() else 0.0

        return EvaluationSummary(
            accuracy = correct.toDouble() / n,
            majorityBaselineAccuracy = majCorrect.toDouble() / n,
            momentumBaselineAccuracy = momCorrect.toDouble() / n,
            macroF1 = macroF1,
            logLoss = logLossSum / n,
            brierScore = brierSum / n,
            classMetrics = classMetrics,
            confusionMatrix = matrix.map { it.toList() }
        )
    }

    private fun runWalkForwardEvaluation(
        dataset: List<LabeledSample>,
        purgeGap: Int,
        numTrees: Int,
        maxDepth: Int,
        minSamplesSplit: Int,
        minLeafSamples: Int,
        seed: Long
    ): Pair<Double, Int> {
        val n = dataset.size
        val minTrain = max(30, (n * 0.40).toInt())
        val foldSize = max(12, ((n - minTrain - purgeGap) / 3))
        if (minTrain + purgeGap + foldSize > n) return Pair(0.0, 0)

        var totalCorrect = 0
        var totalEvaluated = 0
        var folds = 0

        var split = minTrain
        while (split + purgeGap + foldSize <= n && folds < 3) {
            val foldTrain = dataset.subList(0, split)
            val foldTestStart = split + purgeGap
            val foldTestEnd = minOf(n, foldTestStart + foldSize)
            val foldTest = dataset.subList(foldTestStart, foldTestEnd)

            val fitted = fitRandomForest(foldTrain, numTrees, maxDepth, minSamplesSplit, minLeafSamples, seed + folds * 31L)
            for (sample in foldTest) {
                val probs = predictForestProbabilities(fitted.trees, sample.features)
                val predIdx = when {
                    probs[0] >= probs[1] && probs[0] >= probs[2] -> 0
                    probs[1] >= probs[0] && probs[1] >= probs[2] -> 1
                    else -> 2
                }
                if (predIdx == classToIndex(sample.label)) totalCorrect++
                totalEvaluated++
            }
            folds++
            split += foldSize
        }

        val wfAcc = if (totalEvaluated > 0) totalCorrect.toDouble() / totalEvaluated else 0.0
        return Pair(wfAcc, folds)
    }

    private fun countClasses(samples: List<LabeledSample>): Map<MlMovementClass, Int> {
        val map = linkedMapOf(
            MlMovementClass.UPWARD to 0,
            MlMovementClass.DOWNWARD to 0,
            MlMovementClass.SIDEWAYS to 0
        )
        for (s in samples) {
            map[s.label] = (map[s.label] ?: 0) + 1
        }
        return map
    }

    private fun classToIndex(cls: MlMovementClass): Int = when (cls) {
        MlMovementClass.UPWARD -> 0
        MlMovementClass.DOWNWARD -> 1
        MlMovementClass.SIDEWAYS -> 2
    }
}
