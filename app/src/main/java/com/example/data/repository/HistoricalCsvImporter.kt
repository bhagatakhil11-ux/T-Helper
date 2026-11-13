package com.example.data.repository

import com.example.data.model.BatchHistoricalImportResult
import com.example.data.model.Candle
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.HistoricalImportResult
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Strict CSV parser, validator, chronological sorter, and gap detector
 * for genuine historical NSE equity OHLCV datasets.
 *
 * Design guarantees:
 * - Never silently modifies invalid prices, OHLC relationships, volumes, or timestamps.
 * - Rejects duplicates, negative/zero prices, inverted OHLC bars, negative volume, and malformed rows.
 * - Explicitly marks every accepted bar as FeedStatus.HISTORICAL.
 * - Preserves symbol isolation when importing multi-symbol CSV files.
 */
object HistoricalCsvImporter {

    val SUPPORTED_TIMEFRAMES = listOf("1m", "5m", "15m", "1D")

    private val IST_TZ: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private const val MIN_VALID_EPOCH_MS = 631152000000L // 1990-01-01T00:00:00Z
    private const val ONE_DAY_MS = 86_400_000L

    /**
     * Format template showing the exact CSV schema expected by QuantAI.
     * Note: Template header + format examples only — never auto-imported as market data.
     */
    val SAMPLE_CSV_TEMPLATE = """
        timestamp,open,high,low,close,volume
        2025-11-10 09:15:00,1270.00,1274.50,1268.25,1273.10,145200
        2025-11-10 09:16:00,1273.10,1275.80,1272.00,1275.00,98450
        2025-11-10 09:17:00,1275.00,1276.40,1273.50,1274.20,86120
    """.trimIndent()

    private data class ColumnMap(
        val timestampIdx: Int,
        val openIdx: Int,
        val highIdx: Int,
        val lowIdx: Int,
        val closeIdx: Int,
        val volumeIdx: Int,
        val symbolIdx: Int = -1
    )

    private data class ParsedRow(
        val lineNumber: Int,
        val symbol: String,
        val candle: Candle
    )

    fun parseAndValidate(
        inputStream: InputStream,
        defaultSymbol: String,
        timeframe: String,
        sourceName: String,
        useSymbolColumnIfPresent: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): BatchHistoricalImportResult {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        return parseAndValidateLines(
            lines = reader.lineSequence(),
            defaultSymbol = defaultSymbol,
            timeframe = timeframe,
            sourceName = sourceName,
            useSymbolColumnIfPresent = useSymbolColumnIfPresent,
            nowMs = nowMs
        )
    }

    fun parseAndValidateText(
        csvText: String,
        defaultSymbol: String,
        timeframe: String,
        sourceName: String,
        useSymbolColumnIfPresent: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): BatchHistoricalImportResult {
        return parseAndValidateLines(
            lines = csvText.lineSequence(),
            defaultSymbol = defaultSymbol,
            timeframe = timeframe,
            sourceName = sourceName,
            useSymbolColumnIfPresent = useSymbolColumnIfPresent,
            nowMs = nowMs
        )
    }

    private fun parseAndValidateLines(
        lines: Sequence<String>,
        defaultSymbol: String,
        timeframe: String,
        sourceName: String,
        useSymbolColumnIfPresent: Boolean,
        nowMs: Long
    ): BatchHistoricalImportResult {
        if (timeframe !in SUPPORTED_TIMEFRAMES) {
            return BatchHistoricalImportResult(
                success = false,
                errors = listOf("Unsupported timeframe '$timeframe'. Supported: ${SUPPORTED_TIMEFRAMES.joinToString(", ")}")
            )
        }

        val normalizedDefaultSymbol = defaultSymbol.trim().uppercase(Locale.US)
        if (normalizedDefaultSymbol.isBlank()) {
            return BatchHistoricalImportResult(
                success = false,
                errors = listOf("Target NSE symbol cannot be blank.")
            )
        }

        val iterator = lines.iterator()
        var headerLine: String? = null
        var lineNumber = 0
        while (iterator.hasNext()) {
            lineNumber++
            val candidate = iterator.next().trim().removePrefix("\uFEFF")
            if (candidate.isNotBlank() && !candidate.startsWith("#")) {
                headerLine = candidate
                break
            }
        }

        if (headerLine == null) {
            return BatchHistoricalImportResult(
                success = false,
                errors = listOf("CSV file is empty or contains no header row.")
            )
        }

        val delimiter = detectDelimiter(headerLine)
        val headerCols = splitCsvLine(headerLine, delimiter)
        val colMap = mapColumns(headerCols)
            ?: return BatchHistoricalImportResult(
                success = false,
                errors = listOf(
                    "Invalid CSV header. Required columns: timestamp (or date/datetime), open, high, low, close, volume. Found: ${headerCols.joinToString(", ")}"
                )
            )

        val dateParsers = buildDateParsers()
        val maxValidEpochMs = nowMs + ONE_DAY_MS

        var totalRowsRead = 0
        var totalRejected = 0
        var totalDuplicates = 0
        val globalErrors = mutableListOf<String>()
        val globalWarnings = mutableListOf<String>()

        // Partition strictly by symbol so multiple symbols in a CSV never mix
        val symbolBuckets = linkedMapOf<String, MutableList<ParsedRow>>()
        val seenTimestampsBySymbol = mutableMapOf<String, HashSet<Long>>()
        val rejectedBySymbol = mutableMapOf<String, Int>()
        val duplicatesBySymbol = mutableMapOf<String, Int>()

        val minColsRequired = maxOf(
            colMap.timestampIdx, colMap.openIdx, colMap.highIdx, colMap.lowIdx, colMap.closeIdx, colMap.volumeIdx
        ) + 1

        while (iterator.hasNext()) {
            lineNumber++
            val rawLine = iterator.next().trim()
            if (rawLine.isBlank() || rawLine.startsWith("#")) continue
            totalRowsRead++

            val cells = splitCsvLine(rawLine, delimiter)
            if (cells.size < minColsRequired) {
                totalRejected++
                if (globalErrors.size < 25) {
                    globalErrors.add("Line $lineNumber: Expected at least $minColsRequired columns, found ${cells.size}.")
                }
                continue
            }

            val rowSymbol = if (useSymbolColumnIfPresent && colMap.symbolIdx in cells.indices) {
                val sym = cells[colMap.symbolIdx].trim().uppercase(Locale.US)
                    .removeSuffix(".NS").removeSuffix(".BO")
                if (sym.isNotBlank()) sym else normalizedDefaultSymbol
            } else {
                normalizedDefaultSymbol
            }

            val tsRaw = cells[colMap.timestampIdx].trim()
            val timestamp = parseTimestamp(tsRaw, dateParsers)
            if (timestamp == null || timestamp < MIN_VALID_EPOCH_MS || timestamp > maxValidEpochMs) {
                totalRejected++
                rejectedBySymbol[rowSymbol] = (rejectedBySymbol[rowSymbol] ?: 0) + 1
                if (globalErrors.size < 25) {
                    globalErrors.add("Line $lineNumber ($rowSymbol): Invalid or out-of-range timestamp '$tsRaw'.")
                }
                continue
            }

            val open = cells[colMap.openIdx].replace(",", "").toDoubleOrNull()
            val high = cells[colMap.highIdx].replace(",", "").toDoubleOrNull()
            val low = cells[colMap.lowIdx].replace(",", "").toDoubleOrNull()
            val close = cells[colMap.closeIdx].replace(",", "").toDoubleOrNull()
            val volDouble = cells[colMap.volumeIdx].replace(",", "").toDoubleOrNull()

            if (open == null || high == null || low == null || close == null ||
                !open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()
            ) {
                totalRejected++
                rejectedBySymbol[rowSymbol] = (rejectedBySymbol[rowSymbol] ?: 0) + 1
                if (globalErrors.size < 25) {
                    globalErrors.add("Line $lineNumber ($rowSymbol): Non-numeric OHLC value.")
                }
                continue
            }

            if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) {
                totalRejected++
                rejectedBySymbol[rowSymbol] = (rejectedBySymbol[rowSymbol] ?: 0) + 1
                if (globalErrors.size < 25) {
                    globalErrors.add("Line $lineNumber ($rowSymbol): Negative or zero price rejected (O=$open, H=$high, L=$low, C=$close).")
                }
                continue
            }

            val maxOC = maxOf(open, close)
            val minOC = minOf(open, close)
            if (high < low || high < maxOC || low > minOC) {
                totalRejected++
                rejectedBySymbol[rowSymbol] = (rejectedBySymbol[rowSymbol] ?: 0) + 1
                if (globalErrors.size < 25) {
                    globalErrors.add("Line $lineNumber ($rowSymbol): Invalid OHLC relationship (O=$open, H=$high, L=$low, C=$close).")
                }
                continue
            }

            if (volDouble == null || !volDouble.isFinite() || volDouble < 0.0) {
                totalRejected++
                rejectedBySymbol[rowSymbol] = (rejectedBySymbol[rowSymbol] ?: 0) + 1
                if (globalErrors.size < 25) {
                    globalErrors.add("Line $lineNumber ($rowSymbol): Negative or invalid volume '${cells[colMap.volumeIdx]}'.")
                }
                continue
            }
            val volume = volDouble.toLong()

            val seen = seenTimestampsBySymbol.getOrPut(rowSymbol) { HashSet() }
            if (!seen.add(timestamp)) {
                totalDuplicates++
                totalRejected++
                duplicatesBySymbol[rowSymbol] = (duplicatesBySymbol[rowSymbol] ?: 0) + 1
                rejectedBySymbol[rowSymbol] = (rejectedBySymbol[rowSymbol] ?: 0) + 1
                if (globalErrors.size < 25) {
                    globalErrors.add("Line $lineNumber ($rowSymbol): Duplicate candle timestamp '$tsRaw' rejected.")
                }
                continue
            }

            val candle = Candle(
                timestamp = timestamp,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                feedStatus = FeedStatus.HISTORICAL
            )
            symbolBuckets.getOrPut(rowSymbol) { mutableListOf() }.add(ParsedRow(lineNumber, rowSymbol, candle))
        }

        if (totalRowsRead == 0) {
            return BatchHistoricalImportResult(
                success = false,
                errors = listOf("CSV contains header only and 0 data rows.")
            )
        }

        if (symbolBuckets.isEmpty()) {
            return BatchHistoricalImportResult(
                success = false,
                totalRowsRead = totalRowsRead,
                totalRejectedRows = totalRejected,
                totalDuplicates = totalDuplicates,
                errors = if (globalErrors.isNotEmpty()) globalErrors else listOf("All $totalRowsRead rows failed OHLCV validation.")
            )
        }

        val datasetResults = mutableListOf<HistoricalImportResult>()
        var totalAccepted = 0
        var totalMissing = 0

        for ((sym, rows) in symbolBuckets) {
            val wasOutOfOrder = rows.zipWithNext().any { (a, b) -> a.candle.timestamp > b.candle.timestamp }
            val sortedCandles = rows.map { it.candle }.sortedBy { it.timestamp }
            val symRejected = rejectedBySymbol[sym] ?: 0
            val symDuplicates = duplicatesBySymbol[sym] ?: 0

            val gapReport = detectGaps(sortedCandles, timeframe)
            val symWarnings = mutableListOf<String>()
            if (wasOutOfOrder) {
                symWarnings.add("Input rows for $sym were unsorted and have been ordered chronologically.")
            }
            if (symDuplicates > 0) {
                symWarnings.add("Rejected $symDuplicates duplicate timestamp row(s) for $sym.")
            }
            if (symRejected - symDuplicates > 0) {
                symWarnings.add("Rejected ${symRejected - symDuplicates} invalid OHLCV row(s) for $sym.")
            }
            symWarnings.addAll(gapReport.warnings)

            val meta = HistoricalDatasetMetadata(
                symbol = sym,
                timeframe = timeframe,
                sourceName = sourceName,
                feedStatus = FeedStatus.HISTORICAL,
                importedAt = nowMs,
                candleCount = sortedCandles.size,
                startTimestamp = sortedCandles.first().timestamp,
                endTimestamp = sortedCandles.last().timestamp,
                duplicateCount = symDuplicates,
                invalidRowCount = symRejected - symDuplicates,
                missingCandleCount = gapReport.missingCandleCount,
                warnings = symWarnings
            )

            datasetResults.add(
                HistoricalImportResult(
                    success = true,
                    symbol = sym,
                    timeframe = timeframe,
                    metadata = meta,
                    candles = sortedCandles,
                    totalRowsRead = rows.size + symRejected,
                    acceptedCount = sortedCandles.size,
                    rejectedRowCount = symRejected,
                    duplicateCount = symDuplicates,
                    missingCandleCount = gapReport.missingCandleCount,
                    warnings = symWarnings,
                    errors = emptyList()
                )
            )
            totalAccepted += sortedCandles.size
            totalMissing += gapReport.missingCandleCount
            globalWarnings.addAll(symWarnings)
        }

        if (totalRejected > 25 && globalErrors.size >= 25) {
            globalWarnings.add("Additional row errors omitted (showing first 25 of $totalRejected rejected rows).")
        }

        return BatchHistoricalImportResult(
            success = datasetResults.isNotEmpty(),
            importedDatasets = datasetResults,
            totalRowsRead = totalRowsRead,
            totalAcceptedCandles = totalAccepted,
            totalRejectedRows = totalRejected,
            totalDuplicates = totalDuplicates,
            totalMissingCandles = totalMissing,
            warnings = globalWarnings.distinct(),
            errors = globalErrors
        )
    }

    private data class GapReport(
        val missingCandleCount: Int,
        val warnings: List<String>
    )

    private fun detectGaps(sortedCandles: List<Candle>, timeframe: String): GapReport {
        if (sortedCandles.size < 2) return GapReport(0, emptyList())

        val warnings = mutableListOf<String>()
        var missingCount = 0
        val calPrev = Calendar.getInstance(IST_TZ)
        val calCurr = Calendar.getInstance(IST_TZ)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = IST_TZ }

        if (timeframe == "1D") {
            var largestSkippedWeekdays = 0
            var largestGapDate = ""
            for (i in 1 until sortedCandles.size) {
                val prevTs = sortedCandles[i - 1].timestamp
                val currTs = sortedCandles[i].timestamp
                val weekdaysBetween = countWeekdaysStrictlyBetween(prevTs, currTs)
                if (weekdaysBetween > 0) {
                    missingCount += weekdaysBetween
                    if (weekdaysBetween > largestSkippedWeekdays) {
                        largestSkippedWeekdays = weekdaysBetween
                        largestGapDate = dateFmt.format(currTs)
                    }
                }
            }
            if (missingCount > 0) {
                warnings.add(
                    "Detected $missingCount missing weekday daily bar(s) before $largestGapDate (missing trading days or NSE holidays)."
                )
            }
            return GapReport(missingCount, warnings)
        }

        val intervalMs = when (timeframe) {
            "1m" -> 60_000L
            "5m" -> 300_000L
            "15m" -> 900_000L
            else -> 60_000L
        }

        var intradayGaps = 0
        var largestGapBars = 0
        var largestGapDate = ""
        var skippedSessionDays = 0

        for (i in 1 until sortedCandles.size) {
            val prevTs = sortedCandles[i - 1].timestamp
            val currTs = sortedCandles[i].timestamp
            calPrev.timeInMillis = prevTs
            calCurr.timeInMillis = currTs

            val sameIstDay = calPrev.get(Calendar.YEAR) == calCurr.get(Calendar.YEAR) &&
                calPrev.get(Calendar.DAY_OF_YEAR) == calCurr.get(Calendar.DAY_OF_YEAR)

            if (sameIstDay) {
                val diff = currTs - prevTs
                if (diff > intervalMs + 1000L) {
                    val missingInGap = ((diff / intervalMs) - 1L).toInt().coerceAtLeast(0)
                    if (missingInGap > 0) {
                        missingCount += missingInGap
                        intradayGaps++
                        if (missingInGap > largestGapBars) {
                            largestGapBars = missingInGap
                            largestGapDate = dateFmt.format(currTs)
                        }
                    }
                }
            } else {
                skippedSessionDays += countWeekdaysStrictlyBetween(prevTs, currTs)
            }
        }

        if (missingCount > 0) {
            warnings.add(
                "Detected $missingCount missing $timeframe intraday candle(s) across $intradayGaps gap(s) (largest: $largestGapBars bars on $largestGapDate)."
            )
        }
        if (skippedSessionDays > 0) {
            warnings.add(
                "Detected $skippedSessionDays skipped weekday session(s) between trading days (missing sessions or NSE holidays)."
            )
        }

        return GapReport(missingCount, warnings)
    }

    private fun countWeekdaysStrictlyBetween(startMs: Long, endMs: Long): Int {
        val cal = Calendar.getInstance(IST_TZ)
        cal.timeInMillis = startMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val endCal = Calendar.getInstance(IST_TZ)
        endCal.timeInMillis = endMs
        endCal.set(Calendar.HOUR_OF_DAY, 0)
        endCal.set(Calendar.MINUTE, 0)
        endCal.set(Calendar.SECOND, 0)
        endCal.set(Calendar.MILLISECOND, 0)

        var count = 0
        cal.add(Calendar.DAY_OF_MONTH, 1)
        var guard = 0
        while (cal.before(endCal) && guard < 3650) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                count++
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
            guard++
        }
        return count
    }

    private fun detectDelimiter(header: String): Char {
        return when {
            header.count { it == ',' } >= 4 -> ','
            header.count { it == ';' } >= 4 -> ';'
            header.count { it == '\t' } >= 4 -> '\t'
            else -> ','
        }
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    private fun mapColumns(headers: List<String>): ColumnMap? {
        val norm = headers.map {
            it.trim().lowercase(Locale.US).replace("_", "").replace(" ", "").replace(".", "")
        }

        val tsIdx = norm.indexOfFirst {
            it in setOf("timestamp", "time", "datetime", "date", "tradetime", "candle", "bartime")
        }
        val openIdx = norm.indexOfFirst { it in setOf("open", "openprice", "o") }
        val highIdx = norm.indexOfFirst { it in setOf("high", "highprice", "h") }
        val lowIdx = norm.indexOfFirst { it in setOf("low", "lowprice", "l") }
        val closeIdx = norm.indexOfFirst { it in setOf("close", "closeprice", "ltp", "last", "c") }
        val volIdx = norm.indexOfFirst { it in setOf("volume", "vol", "qty", "shares", "sharestraded", "totaltradedquantity", "v") }
        val symIdx = norm.indexOfFirst { it in setOf("symbol", "ticker", "tradingsymbol", "scrip", "stock") }

        if (tsIdx == -1 || openIdx == -1 || highIdx == -1 || lowIdx == -1 || closeIdx == -1 || volIdx == -1) {
            return null
        }
        return ColumnMap(
            timestampIdx = tsIdx,
            openIdx = openIdx,
            highIdx = highIdx,
            lowIdx = lowIdx,
            closeIdx = closeIdx,
            volumeIdx = volIdx,
            symbolIdx = symIdx
        )
    }

    private fun buildDateParsers(): List<SimpleDateFormat> {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd-MMM-yyyy HH:mm:ss",
            "dd-MMM-yyyy HH:mm",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd-MMM-yyyy",
            "dd/MM/yyyy",
            "MM/dd/yyyy"
        )
        return patterns.map { pattern ->
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = IST_TZ
            }
        }
    }

    private val ISO_OFFSET_REGEX = Regex("""^(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2})?)(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})$""")

    private fun parseTimestamp(raw: String, parsers: List<SimpleDateFormat>): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Epoch seconds or milliseconds
        if (trimmed.all { it.isDigit() }) {
            val num = trimmed.toLongOrNull() ?: return null
            return when {
                trimmed.length >= 13 -> num
                trimmed.length in 9..11 -> num * 1000L
                else -> null
            }
        }

        // ISO-8601 with explicit Z or ±HH:MM timezone offset
        val isoMatch = ISO_OFFSET_REGEX.matchEntire(trimmed)
        if (isoMatch != null) {
            val basePart = isoMatch.groupValues[1].replace(' ', 'T')
            val offsetPart = isoMatch.groupValues[2]
            val normalizedOffset = when {
                offsetPart == "Z" -> "GMT"
                offsetPart.contains(":") -> "GMT$offsetPart"
                else -> "GMT${offsetPart.substring(0, 3)}:${offsetPart.substring(3)}"
            }
            val fmtPattern = if (basePart.length == 19) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd'T'HH:mm"
            val fmt = SimpleDateFormat(fmtPattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone(normalizedOffset)
            }
            return runCatching { fmt.parse(basePart)?.time }.getOrNull()
        }

        for (fmt in parsers) {
            val parsed = runCatching { fmt.parse(trimmed)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
