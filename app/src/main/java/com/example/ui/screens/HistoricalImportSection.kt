package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BatchHistoricalImportResult
import com.example.data.model.DataOrigin
import com.example.data.model.DataQualityThresholds
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.ResearchQualityStatus
import com.example.data.model.VerificationStatus
import com.example.data.repository.HistoricalCsvImporter
import com.example.ui.theme.CardBorder
import com.example.ui.theme.CardDark
import com.example.ui.theme.CardDarkElevated
import com.example.ui.theme.QuantCyan
import com.example.ui.theme.QuantCyanDim
import com.example.ui.theme.QuantGold
import com.example.ui.theme.QuantGreen
import com.example.ui.theme.QuantGreenDim
import com.example.ui.theme.QuantRed
import com.example.ui.theme.QuantRedDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.TradingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun HistoricalImportSection(viewModel: TradingViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val selectedSymbol by viewModel.selectedSymbol.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
    val instruments by viewModel.instruments.collectAsState()
    val historicalDatasets by viewModel.historicalDatasets.collectAsState()
    val activeProvenance by viewModel.activeDatasetProvenance.collectAsState()
    val isImportingCsv by viewModel.isImportingCsv.collectAsState()
    val lastImportResult by viewModel.lastImportResult.collectAsState()
    val templateExportStatus by viewModel.templateExportStatus.collectAsState()

    var targetSymbolInput by remember { mutableStateOf(selectedSymbol) }
    var useSymbolColumn by remember { mutableStateOf(true) }
    var showPasteEditor by remember { mutableStateOf(false) }
    var pastedCsvText by remember { mutableStateOf("") }
    var showTemplatePreview by remember { mutableStateOf(false) }
    var localCopyStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedSymbol) {
        if (targetSymbolInput.isBlank()) {
            targetSymbolInput = selectedSymbol
        }
    }

    val openCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolvedSym = targetSymbolInput.trim().uppercase().ifBlank { selectedSymbol }
            viewModel.importHistoricalCsvFromUri(
                context = context,
                uri = uri,
                targetSymbol = resolvedSym,
                timeframe = selectedTimeframe,
                useSymbolColumnIfPresent = useSymbolColumn
            )
        }
    }

    val saveTemplateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportSampleCsvTemplate(context, uri)
        }
    }

    Text(
        text = "HISTORICAL NSE DATA INGESTION & PROVENANCE",
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
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Active Feed Provenance Banner
            val prov = activeProvenance
            val verification = prov?.verificationStatus ?: VerificationStatus.SIMULATED_NOT_REAL
            val (badgeText, badgeColor, badgeBg) = when (verification) {
                VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR -> Triple("VERIFIED HISTORICAL", QuantGreen, QuantGreenDim)
                VerificationStatus.IMPORTED_CSV_UNVERIFIED -> Triple("IMPORTED_CSV_UNVERIFIED", QuantCyan, QuantCyanDim)
                VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA -> Triple("TEST_FIXTURE (NOT REAL)", QuantGold, Color(0x33FFC107))
                VerificationStatus.SIMULATED_NOT_REAL -> Triple("SIMULATED (NOT REAL)", QuantGold, Color(0x33FFC107))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardDarkElevated)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(badgeBg, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$selectedSymbol • $selectedTimeframe",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (prov != null) {
                        val reqBars = DataQualityThresholds.requiredResearchBars(prov.timeframe)
                        val qualityStatus = DataQualityThresholds.evaluateQuality(prov.candleCount, prov.timeframe)
                        Text(
                            text = "${prov.sourceName} | ${prov.candleCount} bars | ${formatIstRange(prov.startTimestamp, prov.endTimestamp)}",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "ID: ${prov.datasetId} | SHA256: ${prov.sha256Checksum.take(12)} | TZ: ${prov.timezone} | Adj: ${prov.adjustmentPolicy.name}",
                            color = TextSubtle,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (qualityStatus == ResearchQualityStatus.LOW_CONFIDENCE_INSUFFICIENT_DATA) {
                            Text(
                                text = "LOW_CONFIDENCE_INSUFFICIENT_DATA: ${prov.candleCount}/$reqBars research-grade bars for ${prov.timeframe}",
                                color = QuantGold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (prov.missingCandleCount > 0 || prov.holidaySkippedCount > 0) {
                            Text(
                                text = "Gaps: ${prov.missingCandleCount} missing bar(s) | NSE holidays skipped: ${prov.holidaySkippedCount}",
                                color = if (prov.missingCandleCount > 0) QuantGold else TextSubtle,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (prov.corporateActionFlags.isNotEmpty()) {
                            Text(
                                text = "Corporate-action / jump flag(s): ${prov.corporateActionFlags.size} (${prov.corporateActionFlags.first()})",
                                color = QuantGold,
                                fontSize = 9.sp
                            )
                        }
                    } else {
                        Text(
                            text = "No historical CSV imported for $selectedSymbol ($selectedTimeframe). Synthetic paper feed active (not real NSE market history).",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Target Symbol Input + Quick Watchlist Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = targetSymbolInput,
                    onValueChange = { targetSymbolInput = it.uppercase() },
                    label = { Text("Default NSE Symbol", fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuantCyan,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedLabelColor = QuantCyan,
                        unfocusedLabelColor = TextMuted
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_symbol_input")
                )

                // Timeframe Selector
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HistoricalCsvImporter.SUPPORTED_TIMEFRAMES.forEach { tf ->
                        val isTfSel = tf == selectedTimeframe
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isTfSel) QuantCyanDim else CardDarkElevated)
                                .border(1.dp, if (isTfSel) QuantCyan else CardBorder, RoundedCornerShape(6.dp))
                                .clickable { viewModel.setTimeframe(tf) }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                .testTag("import_tf_$tf")
                        ) {
                            Text(
                                text = tf,
                                color = if (isTfSel) QuantCyan else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Quick Watchlist Symbol Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                instruments.filter { it.isTradable }.forEach { inst ->
                    val isSel = inst.symbol == targetSymbolInput.trim().uppercase()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) QuantCyanDim else CardDarkElevated)
                            .clickable {
                                targetSymbolInput = inst.symbol
                                viewModel.selectSymbol(inst.symbol)
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = inst.symbol,
                            color = if (isSel) QuantCyan else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Multi-symbol CSV column toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-partition CSV 'symbol' column", color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("Isolates multiple NSE tickers without cross-contamination", color = TextSubtle, fontSize = 10.sp)
                }
                Switch(
                    checked = useSymbolColumn,
                    onCheckedChange = { useSymbolColumn = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = QuantCyan
                    )
                )
            }

            // Primary Import Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        openCsvLauncher.launch(arrayOf("text/*", "application/csv", "text/comma-separated-values", "*/*"))
                    },
                    enabled = !isImportingCsv,
                    colors = ButtonDefaults.buttonColors(containerColor = QuantCyan),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("select_csv_file_button")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Select CSV File", tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import CSV File", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { showPasteEditor = !showPasteEditor },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("toggle_paste_csv_button")
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = "Paste CSV", tint = QuantCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showPasteEditor) "Hide Paste" else "Paste CSV", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = { showTemplatePreview = !showTemplatePreview },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("toggle_csv_template_button")
                ) {
                    Icon(Icons.Default.Description, contentDescription = "Sample CSV Template", tint = TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Template", fontSize = 11.sp)
                }
            }

            // Paste CSV Editor
            if (showPasteEditor) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pastedCsvText,
                        onValueChange = { pastedCsvText = it },
                        label = { Text("Paste historical NSE OHLCV CSV rows", fontSize = 11.sp) },
                        placeholder = { Text("timestamp,open,high,low,close,volume", fontSize = 11.sp, color = TextSubtle) },
                        minLines = 4,
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = QuantCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("paste_csv_input")
                    )
                    Button(
                        onClick = {
                            val resolvedSym = targetSymbolInput.trim().uppercase().ifBlank { selectedSymbol }
                            viewModel.importHistoricalCsvText(
                                csvText = pastedCsvText,
                                targetSymbol = resolvedSym,
                                timeframe = selectedTimeframe,
                                useSymbolColumnIfPresent = useSymbolColumn
                            )
                        },
                        enabled = !isImportingCsv && pastedCsvText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = QuantGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("import_pasted_csv_button")
                    ) {
                        Text("Validate & Import Pasted CSV", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Sample CSV Template Preview & Export
            if (showTemplatePreview) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardDarkElevated)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SAMPLE NSE OHLCV CSV FORMAT TEMPLATE",
                        color = QuantCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = HistoricalCsvImporter.SAMPLE_CSV_TEMPLATE,
                        color = TextWhite,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(HistoricalCsvImporter.SAMPLE_CSV_TEMPLATE))
                                localCopyStatus = "Template copied to clipboard."
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Template", tint = QuantCyan, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", color = TextWhite, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { saveTemplateLauncher.launch("quantai_nse_ohlcv_template.csv") },
                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Save Template CSV", tint = QuantGreen, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save .csv", color = TextWhite, fontSize = 11.sp)
                        }
                    }
                    val statusMsg = templateExportStatus ?: localCopyStatus
                    if (!statusMsg.isNullOrBlank()) {
                        Text(statusMsg, color = QuantGreen, fontSize = 10.sp)
                    }
                }
            }

            // Import Progress Bar
            if (isImportingCsv) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = QuantCyan,
                        trackColor = CardDarkElevated
                    )
                    Text("Parsing, validating OHLCV invariants & checking candle gaps...", color = QuantCyan, fontSize = 10.sp)
                }
            }

            // Last Import Summary & Warnings/Errors
            lastImportResult?.let { res ->
                ImportResultSummaryCard(res)
            }

            // Stored Historical Datasets List
            if (historicalDatasets.isNotEmpty()) {
                val sortedDatasets = remember(historicalDatasets) {
                    historicalDatasets.values.sortedByDescending { it.importedAt }
                }
                Text(
                    text = "STORED HISTORICAL DATASETS (${sortedDatasets.size})",
                    color = TextSubtle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sortedDatasets.forEach { ds ->
                        StoredDatasetRow(
                            dataset = ds,
                            isSelected = ds.symbol == selectedSymbol && ds.timeframe == selectedTimeframe,
                            onSelect = {
                                targetSymbolInput = ds.symbol
                                viewModel.selectSymbol(ds.symbol)
                                viewModel.setTimeframe(ds.timeframe)
                                viewModel.runBacktest(ds.symbol, ds.timeframe)
                            },
                            onDelete = {
                                viewModel.deleteHistoricalDataset(ds.symbol, ds.timeframe)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportResultSummaryCard(res: BatchHistoricalImportResult) {
    val borderCol = if (res.success) QuantGreen.copy(alpha = 0.45f) else QuantRed.copy(alpha = 0.5f)
    val bgCol = if (res.success) QuantGreenDim.copy(alpha = 0.4f) else QuantRedDim.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgCol)
            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (res.success) QuantGreen else QuantRed,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (res.success) {
                    "IMPORTED ${res.totalAcceptedCandles} VALID CANDLES (${res.importedDatasets.size} DATASET)"
                } else {
                    "CSV IMPORT FAILED"
                },
                color = if (res.success) QuantGreen else QuantRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Rows Read: ${res.totalRowsRead} | Accepted: ${res.totalAcceptedCandles} | Rejected: ${res.totalRejectedRows} | Duplicates: ${res.totalDuplicates} | Missing Gaps: ${res.totalMissingCandles}",
            color = TextWhite,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )

        res.importedDatasets.forEach { ds ->
            val meta = ds.metadata
            if (meta != null) {
                Text(
                    text = "• ${ds.symbol} (${ds.timeframe}): ${ds.acceptedCount} bars | ${formatIstRange(meta.startTimestamp, meta.endTimestamp)}",
                    color = QuantCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (res.warnings.isNotEmpty()) {
            res.warnings.take(4).forEach { w ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = QuantGold, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(w, color = QuantGold, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }

        if (res.errors.isNotEmpty()) {
            res.errors.take(4).forEach { err ->
                Text("• $err", color = QuantRed, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun StoredDatasetRow(
    dataset: HistoricalDatasetMetadata,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) QuantCyanDim.copy(alpha = 0.45f) else CardDarkElevated)
            .border(1.dp, if (isSelected) QuantCyan else CardBorder, RoundedCornerShape(10.dp))
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val (dsBadgeLabel, dsBadgeColor, dsBadgeBg) = when (dataset.verificationStatus) {
                VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR -> Triple("VERIFIED", QuantGreen, QuantGreenDim)
                VerificationStatus.IMPORTED_CSV_UNVERIFIED -> Triple("CSV_UNVERIFIED", QuantCyan, QuantCyanDim)
                VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA -> Triple("TEST_FIXTURE", QuantGold, Color(0x33FFC107))
                VerificationStatus.SIMULATED_NOT_REAL -> Triple("SIMULATED", QuantGold, Color(0x33FFC107))
            }
            val rowQuality = DataQualityThresholds.evaluateQuality(dataset.candleCount, dataset.timeframe)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${dataset.symbol} • ${dataset.timeframe}",
                    color = if (isSelected) QuantCyan else TextWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(dsBadgeBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(dsBadgeLabel, color = dsBadgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                if (dataset.missingCandleCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${dataset.missingCandleCount} gaps", color = QuantGold, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                text = "${dataset.candleCount} bars | ${dataset.sourceName} | ${rowQuality.name}",
                color = TextMuted,
                fontSize = 10.sp
            )
            Text(
                text = "ID: ${dataset.datasetId.ifBlank { "--" }} | SHA256: ${dataset.sha256Checksum.take(10)} | TZ: ${dataset.timezone} | Adj: ${dataset.adjustmentPolicy.name}",
                color = TextSubtle,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = formatIstRange(dataset.startTimestamp, dataset.endTimestamp),
                color = TextSubtle,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete ${dataset.symbol} ${dataset.timeframe} dataset",
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatIstRange(startMs: Long, endMs: Long): String {
    if (startMs <= 0L || endMs <= 0L) return "--"
    val fmt = SimpleDateFormat("dd MMM yy HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }
    return "${fmt.format(Date(startMs))} → ${fmt.format(Date(endMs))} IST"
}
