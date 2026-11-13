package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BacktestResult
import com.example.data.model.DecisionType
import com.example.data.model.FeedStatus
import com.example.data.model.MlMovementClass
import com.example.data.model.ResearchQualityStatus
import com.example.data.model.VerificationStatus
import com.example.data.model.formatInr
import com.example.engine.indicators.CandlestickPattern
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.example.ui.theme.CardBorder
import com.example.ui.theme.CardDark
import com.example.ui.theme.CardDarkElevated
import com.example.ui.theme.QuantCyan
import com.example.ui.theme.QuantCyanDim
import com.example.ui.theme.QuantGold
import com.example.ui.theme.QuantGreen
import com.example.ui.theme.QuantGreenDim
import com.example.ui.theme.QuantPurple
import com.example.ui.theme.QuantPurpleDim
import com.example.ui.theme.QuantRed
import com.example.ui.theme.QuantRedDim
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.TradingViewModel

@Composable
fun StrategyLabScreen(viewModel: TradingViewModel) {
    val snapshot by viewModel.currentSnapshot.collectAsState()
    val selectedSymbol by viewModel.selectedSymbol.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
    val lastDecision by viewModel.lastDecision.collectAsState()
    val backtestResult by viewModel.backtestResult.collectAsState()
    val isBacktesting by viewModel.isBacktesting.collectAsState()
    val unseenTestOnly by viewModel.unseenTestBacktestOnly.collectAsState()
    val instruments by viewModel.instruments.collectAsState()
    val currentMlPrediction by viewModel.currentMlPrediction.collectAsState()
    val isTrainingMl by viewModel.isTrainingMl.collectAsState()
    val lastMlTrainingSummary by viewModel.lastMlTrainingSummary.collectAsState()
    val mlValidationReport by viewModel.mlValidationReport.collectAsState()

    var backtestSymbol by remember { mutableStateOf(selectedSymbol) }

    LaunchedEffect(selectedSymbol) {
        backtestSymbol = selectedSymbol
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AI Strategy Lab",
                    color = TextWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Multi-Factor Ensemble & Machine Learning Pipeline",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            Box(
                modifier = Modifier
                    .background(QuantPurpleDim, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "4 Strategies + ML",
                    color = QuantPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Phase 2: Real Historical NSE CSV Ingestion & Data Provenance
        HistoricalImportSection(viewModel = viewModel)

        // Live Technical Indicators Matrix Card
        Text(
            text = "LIVE QUANT INDICATOR MATRIX ($selectedSymbol)",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (snapshot != null) {
                    val s = snapshot!!
                    // Row 1: RSI and Pattern
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("RSI (14)", color = TextMuted, fontSize = 11.sp)
                            val rsiColor = if (s.rsi14 < 30) QuantGreen else if (s.rsi14 > 70) QuantRed else QuantCyan
                            Text(
                                text = String.format("%.1f", s.rsi14),
                                color = rsiColor,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            val rsiStatus = if (s.rsi14 < 30) "Oversold" else if (s.rsi14 > 70) "Overbought" else "Neutral"
                            Text(rsiStatus, color = rsiColor.copy(alpha = 0.8f), fontSize = 10.sp)
                        }

                        Column {
                            Text("MACD (12, 26, 9)", color = TextMuted, fontSize = 11.sp)
                            val histColor = if (s.macd.histogram >= 0) QuantGreen else QuantRed
                            Text(
                                text = "Hist: ${if (s.macd.histogram >= 0) "+" else ""}${String.format("%.2f", s.macd.histogram)}",
                                color = histColor,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Line: ${String.format("%.2f", s.macd.macd)} | Sig: ${String.format("%.2f", s.macd.signal)}", color = TextSubtle, fontSize = 10.sp)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Candle Pattern", color = TextMuted, fontSize = 11.sp)
                            val patternColor = when (s.detectedPattern) {
                                CandlestickPattern.BULLISH_ENGULFING, CandlestickPattern.MORNING_STAR, CandlestickPattern.HAMMER -> QuantGreen
                                CandlestickPattern.BEARISH_ENGULFING, CandlestickPattern.EVENING_STAR, CandlestickPattern.SHOOTING_STAR -> QuantRed
                                else -> TextWhite
                            }
                            Text(
                                text = s.detectedPattern.name.replace("_", " "),
                                color = patternColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Row 2: Bollinger Bands & ATR & VWAP
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Bollinger Bands (20, 2)", color = TextMuted, fontSize = 11.sp)
                            Text("U: ₹${String.format("%.1f", s.bollinger.upper)}", color = TextWhite, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("L: ₹${String.format("%.1f", s.bollinger.lower)}", color = TextWhite, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }

                        Column {
                            Text("ATR (14 Volatility)", color = TextMuted, fontSize = 11.sp)
                            Text("₹${String.format("%.2f", s.atr14)}", color = QuantGold, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("VWAP Benchmark", color = TextMuted, fontSize = 11.sp)
                            Text("₹${String.format("%.2f", s.vwap)}", color = QuantCyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Row 3: EMAs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("EMA 9: ₹${String.format("%.1f", s.ema9)}", color = QuantCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("EMA 21: ₹${String.format("%.1f", s.ema21)}", color = QuantPurple, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("EMA 50: ₹${String.format("%.1f", s.ema50)}", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    Text("Loading indicator stream...", color = TextMuted, fontSize = 12.sp)
                }
            }
        }

        // Dedicated Machine Learning Prediction Engine Section
        Text(
            text = "MACHINE LEARNING PREDICTION ENGINE (RANDOM FOREST / GBDT)",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val ml = currentMlPrediction
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = QuantPurple, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("QuantAI Random Forest", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            val statusTag = when {
                                ml?.isTrained != true -> "UNTRAINED (Rule Ensemble Active)"
                                else -> "${ml.modelVersion} • ${ml.featureVersion} • ${ml.verificationStatus.name}"
                            }
                            val statusTagColor = when {
                                ml?.isTrained != true -> TextSubtle
                                ml.verificationStatus == VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR -> QuantGreen
                                ml.verificationStatus == VerificationStatus.IMPORTED_CSV_UNVERIFIED -> QuantCyan
                                else -> QuantGold
                            }
                            Text(
                                text = statusTag,
                                color = statusTagColor,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.trainMlModel(selectedSymbol) },
                        enabled = !isTrainingMl,
                        colors = ButtonDefaults.buttonColors(containerColor = CardDarkElevated),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("train_ml_button")
                    ) {
                        if (isTrainingMl) {
                            CircularProgressIndicator(color = QuantPurple, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Training RF...", color = QuantPurple, fontSize = 11.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = QuantPurple, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Train & Validate", color = QuantPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (ml != null && ml.isTrained) {
                    // Probability Breakdown Bar
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("P(Upward): ${String.format("%.1f", ml.probUpward * 100)}%", color = QuantGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("P(Sideways): ${String.format("%.1f", ml.probSideways * 100)}%", color = QuantGold, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("P(Downward): ${String.format("%.1f", ml.probDownward * 100)}%", color = QuantRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(6.dp))

                        // Segmented bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            Box(modifier = Modifier.weight(ml.probUpward.toFloat().coerceAtLeast(0.02f)).fillMaxSize().background(QuantGreen))
                            Box(modifier = Modifier.weight(ml.probSideways.toFloat().coerceAtLeast(0.02f)).fillMaxSize().background(QuantGold))
                            Box(modifier = Modifier.weight(ml.probDownward.toFloat().coerceAtLeast(0.02f)).fillMaxSize().background(QuantRed))
                        }
                    }

                    // Real Gini Feature Importance Tags
                    val sortedImportances = ml.featureImportances.entries.sortedByDescending { it.value }
                    if (sortedImportances.isNotEmpty()) {
                        Text("GINI FEATURE IMPORTANCES (LEARNED)", color = TextSubtle, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sortedImportances.forEach { (feat, weight) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CardDarkElevated)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("$feat: ${String.format("%.2f", weight)}", color = TextWhite, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Model is untrained. Tap Train & Validate to fit a 35-tree CART Random Forest on purged chronological splits.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                // Training Summary
                Text(
                    text = lastMlTrainingSummary,
                    color = QuantCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Detailed Unseen Test Validation Report
                val rep = mlValidationReport
                if (rep != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardDarkElevated)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val repColor = when (rep.verificationStatus) {
                                VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR -> QuantGreen
                                VerificationStatus.IMPORTED_CSV_UNVERIFIED -> QuantCyan
                                else -> QuantGold
                            }
                            Text(
                                text = "VALIDATION (${rep.verificationStatus.name})",
                                color = repColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Split: ${rep.trainSamples}/${rep.validationSamples}/${rep.testSamples} (Purged ${rep.purgedSamples})",
                                color = TextSubtle,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "Dataset: ${rep.datasetId.ifBlank { "SIMULATED" }} | ${rep.featureVersion} | ${rep.researchQualityStatus.name} (req >= ${rep.researchMinRequiredBars})",
                            color = if (rep.researchQualityStatus == ResearchQualityStatus.RESEARCH_GRADE) QuantGreen else QuantGold,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Test Acc", color = TextMuted, fontSize = 10.sp)
                                Text("${String.format("%.1f", rep.testAccuracy * 100)}%", color = QuantCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Column {
                                Text("Maj Base", color = TextMuted, fontSize = 10.sp)
                                Text("${String.format("%.1f", rep.baselineMajorityAccuracy * 100)}%", color = TextWhite, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Column {
                                Text("Mom Base", color = TextMuted, fontSize = 10.sp)
                                Text("${String.format("%.1f", rep.baselineMomentumAccuracy * 100)}%", color = TextWhite, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Macro F1", color = TextMuted, fontSize = 10.sp)
                                Text(String.format("%.2f", rep.macroF1), color = QuantPurple, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("LogLoss: ${String.format("%.3f", rep.testLogLoss)}", color = TextSubtle, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("Brier: ${String.format("%.3f", rep.testBrierScore)}", color = TextSubtle, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("WF Acc(${rep.walkForwardFolds}f): ${String.format("%.1f", rep.walkForwardAccuracy * 100)}%", color = TextSubtle, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }

                        if (rep.confusionMatrix.size == 3) {
                            Text("CONFUSION MATRIX (Actual Rows x Pred Cols: UP / DN / FLAT)", color = TextSubtle, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            val rowLabels = listOf("UP  ", "DN  ", "FLAT")
                            rep.confusionMatrix.forEachIndexed { idx, row ->
                                Text(
                                    text = "${rowLabels[idx]} -> [ ${row.joinToString(" | ") { it.toString().padStart(2) }} ]",
                                    color = TextWhite,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Text(
                            text = rep.validationGateReason,
                            color = if (rep.passedValidationGate) QuantGreen else QuantRed,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Ensemble Voting Breakdown
        Text(
            text = "STRATEGY ENSEMBLE VOTING WEIGHTS",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        val scores = lastDecision?.strategyScores ?: emptyList()
        if (scores.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                scores.forEach { sc ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sc.strategyName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Weight ${(sc.weight * 100).toInt()}%", color = TextSubtle, fontSize = 10.sp)
                                }

                                val sigColor = when (sc.signal) {
                                    DecisionType.BUY -> QuantGreen
                                    DecisionType.SELL -> QuantRed
                                    DecisionType.HOLD -> QuantGold
                                    DecisionType.NO_TRADE -> TextMuted
                                }
                                val sigBg = when (sc.signal) {
                                    DecisionType.BUY -> QuantGreenDim
                                    DecisionType.SELL -> QuantRedDim
                                    DecisionType.HOLD -> Color(0x33FFC107)
                                    DecisionType.NO_TRADE -> CardDarkElevated
                                }

                                Box(
                                    modifier = Modifier
                                        .background(sigBg, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${sc.signal.name} (${String.format("%.2f", sc.score)})",
                                        color = sigColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(sc.rationale, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
        }

        // Single-Series & Holdout Backtesting Lab Card
        Text(
            text = "SINGLE-SERIES & HOLDOUT BACKTESTER",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Simulation Target", color = TextMuted, fontSize = 11.sp)
                        Text("$backtestSymbol • $selectedTimeframe", color = QuantCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.runBacktest(backtestSymbol, selectedTimeframe, unseenTestOnly) },
                        enabled = !isBacktesting,
                        colors = ButtonDefaults.buttonColors(containerColor = QuantCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("run_backtest_button")
                    ) {
                        if (isBacktesting) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Simulating...", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run Backtest", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Symbol selector chips for backtest
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    instruments.filter { it.isTradable }.forEach { inst ->
                        val isSel = inst.symbol == backtestSymbol
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) QuantCyanDim else CardDarkElevated)
                                .clickable {
                                    backtestSymbol = inst.symbol
                                    viewModel.runBacktest(inst.symbol, selectedTimeframe, unseenTestOnly)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = inst.symbol,
                                color = if (isSel) QuantCyan else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Unseen Test Split Toggle (prevents backtest from reusing Train/Val bars when ML is trained)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardDarkElevated)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unseen Test Split Only (Leak-Free)", color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("Evaluates backtest strictly after purged ML Train/Validation bars", color = TextSubtle, fontSize = 9.sp)
                    }
                    Switch(
                        checked = unseenTestOnly,
                        onCheckedChange = { enabled ->
                            viewModel.setUnseenTestBacktestOnly(enabled)
                            viewModel.runBacktest(backtestSymbol, selectedTimeframe, enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = QuantGreen
                        ),
                        modifier = Modifier.testTag("unseen_test_backtest_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Backtest Results Metrics Grid
                if (backtestResult != null) {
                    val b = backtestResult!!
                    val (statusColor, statusBg) = when (b.verificationStatus) {
                        VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR -> QuantGreen to QuantGreenDim
                        VerificationStatus.IMPORTED_CSV_UNVERIFIED -> QuantCyan to QuantCyanDim
                        else -> QuantGold to Color(0x33FFC107)
                    }

                    // Provenance & Evaluation Scope Strip
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CardDarkElevated)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(statusBg, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = b.verificationStatus.name,
                                    color = statusColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${b.evaluatedBarCount} eval / ${b.candleCount} total bars",
                                color = TextSubtle,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text("Source: ${b.sourceName} | ID: ${b.datasetId.ifBlank { "SIMULATED" }}", color = TextMuted, fontSize = 10.sp)
                        Text("Scope: ${b.evaluationScope}", color = QuantCyan, fontSize = 10.sp)
                        Text(
                            text = "Quality: ${b.researchQualityStatus.name}",
                            color = if (b.researchQualityStatus == ResearchQualityStatus.RESEARCH_GRADE) QuantGreen else QuantGold,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("Exec: ${b.executionAssumption}", color = TextSubtle, fontSize = 9.sp)
                        if (b.costModelSummary.isNotBlank()) {
                            Text("Costs: ${b.costModelSummary}", color = TextSubtle, fontSize = 9.sp)
                        }
                        if (b.startTimestamp > 0L && b.endTimestamp > 0L) {
                            Text("Range: ${formatBacktestRange(b.startTimestamp, b.endTimestamp)}", color = TextSubtle, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Win Rate", color = TextMuted, fontSize = 11.sp)
                            val winColor = if (b.winRatePercent >= 50) QuantGreen else QuantRed
                            Text(
                                text = "${String.format("%.1f", b.winRatePercent)}%",
                                color = winColor,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text("${b.winningTrades}W / ${b.losingTrades}L (${b.totalTrades} trades)", color = TextSubtle, fontSize = 10.sp)
                        }

                        Column {
                            Text("Profit Factor", color = TextMuted, fontSize = 11.sp)
                            val pfColor = if (b.profitFactor >= 1.5) QuantGreen else if (b.profitFactor >= 1.0) QuantGold else QuantRed
                            Text(
                                text = String.format("%.2f", b.profitFactor),
                                color = pfColor,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Gross Gains/Losses", color = TextSubtle, fontSize = 10.sp)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Expectancy", color = TextMuted, fontSize = 11.sp)
                            val expColor = if (b.expectancy >= 0) QuantGreen else QuantRed
                            Text(
                                text = "${if (b.expectancy >= 0) "+" else ""}${formatInr(b.expectancy)}",
                                color = expColor,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Per trade avg", color = TextSubtle, fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Net Backtest Return", color = TextMuted, fontSize = 11.sp)
                            val netColor = if (b.netProfit >= 0) QuantGreen else QuantRed
                            Text(
                                text = "${if (b.netProfit >= 0) "+" else ""}${formatInr(b.netProfit)} (${if (b.netProfit >= 0) "+" else ""}${String.format("%.2f", b.netProfitPercent)}%)",
                                color = netColor,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column {
                            Text("Max Drawdown", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = "-${String.format("%.2f", b.maxDrawdownPercent)}%",
                                color = QuantRed,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Sharpe Ratio", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = b.sharpeRatio?.let { String.format("%.2f", it) } ?: "N/A",
                                color = if (b.sharpeRatio != null) QuantCyan else QuantGold,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (b.sharpeStatusReason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Sharpe note: ${b.sharpeStatusReason}",
                            color = if (b.sharpeRatio != null) TextSubtle else QuantGold,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Friction telemetry
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Slippage: -${formatInr(b.totalSlippageDeducted)}", color = TextSubtle, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("Indian Levies (STT/GST): -${formatInr(b.totalChargesDeducted)}", color = TextSubtle, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    // Attached ML Out-Of-Sample Classification & Baseline Comparison
                    b.mlValidationReport?.let { mlRep ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardDarkElevated)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "ML EVALUATION VS BASELINES (TRAIN ${mlRep.trainSamples} / VAL ${mlRep.validationSamples} / UNSEEN TEST ${mlRep.testSamples} / PURGED ${mlRep.purgedSamples})",
                                color = QuantPurple,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Test Acc: ${String.format("%.1f", mlRep.testAccuracy * 100)}% | Maj Base: ${String.format("%.1f", mlRep.baselineMajorityAccuracy * 100)}% | Mom Base: ${String.format("%.1f", mlRep.baselineMomentumAccuracy * 100)}% | Macro F1: ${String.format("%.2f", mlRep.macroF1)}",
                                color = TextWhite,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            mlRep.classMetrics.forEach { cm ->
                                Text(
                                    text = "${cm.movementClass.name.padEnd(8)} P=${String.format("%.2f", cm.precision)} R=${String.format("%.2f", cm.recall)} F1=${String.format("%.2f", cm.f1Score)} (n=${cm.support})",
                                    color = TextMuted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (mlRep.confusionMatrix.size == 3) {
                                val labels = listOf("UP  ", "DN  ", "FLAT")
                                mlRep.confusionMatrix.forEachIndexed { idx, row ->
                                    Text(
                                        text = "CM ${labels[idx]}: [ ${row.joinToString(" | ") { it.toString().padStart(2) }} ]",
                                        color = TextSubtle,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Equity Curve Canvas Chart
                    Text("EQUITY CURVE SIMULATION", color = TextSubtle, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    EquityCurveChart(
                        equityPoints = b.equityCurve,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

private fun formatBacktestRange(startMs: Long, endMs: Long): String {
    val fmt = SimpleDateFormat("dd MMM yy HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }
    return "${fmt.format(Date(startMs))} → ${fmt.format(Date(endMs))} IST"
}

@Composable
fun EquityCurveChart(
    equityPoints: List<Double>,
    modifier: Modifier = Modifier
) {
    if (equityPoints.size < 2) return

    val minEquity = remember(equityPoints) { equityPoints.minOrNull() ?: 500000.0 }
    val maxEquity = remember(equityPoints) { equityPoints.maxOrNull() ?: 500000.0 }
    val range = remember(minEquity, maxEquity) { (maxEquity - minEquity).coerceAtLeast(100.0) }
    val isNetProfit = remember(equityPoints) { equityPoints.last() >= equityPoints.first() }
    val curveColor = if (isNetProfit) QuantGreen else QuantRed

    Box(
        modifier = modifier
            .background(CardDarkElevated, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val step = w / (equityPoints.size - 1)

            val path = Path()
            val fillPath = Path()

            for (i in equityPoints.indices) {
                val x = i * step
                val normalizedY = ((equityPoints[i] - minEquity) / range).toFloat()
                val y = h * (1f - normalizedY)

                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            fillPath.lineTo(w, h)
            fillPath.close()

            drawPath(fillPath, curveColor.copy(alpha = 0.12f))
            drawPath(path, curveColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
    }
}
