package com.example.data

import com.example.model.DtcCode
import com.example.model.DtcSeverity
import com.example.model.TruckModule

object SitrakFaultCodes {

    val sampleActiveFaults: List<DtcCode> = listOf(
        DtcCode(
            id = "DTC_01",
            spnFmi = "SPN 102 FMI 3",
            obdCode = "P0238",
            module = TruckModule.ECM,
            title = "Датчик давления наддува — высокое напряжение цепи",
            description = "Сигнал с датчика абсолютного давления во впускном коллекторе (MAP/Boost) превышает 4.85V. Короткое замыкание сигнального провода на бортовую сеть +24V или опорные 5V.",
            probableCauses = listOf(
                "Повреждение жгута проводки от датчика наддува к разъему ЭБУ EDC17",
                "Окисление или замыкание контактов разъема датчика на впускном тракте",
                "Неисправность чувствительного пьезоэлемента датчика давления",
                "Дефект опорного напряжения +5В в блоке управления двигателем"
            ),
            severity = DtcSeverity.WARNING,
            isActive = true,
            freezeFrame = mapOf(
                "Обороты двигателя" to "1420 об/мин",
                "Скорость" to "74 км/ч",
                "Температура ОЖ" to "86 °C",
                "Давление наддува" to "2.42 бар",
                "Давление в рампе" to "1380 бар"
            )
        ),
        DtcCode(
            id = "DTC_02",
            spnFmi = "SPN 1761 FMI 1",
            obdCode = "P204F",
            module = TruckModule.SCR,
            title = "Уровень реагента AdBlue (мочевины) критически низкий",
            description = "Уровень восстановителя в баке нейтрализатора опустился ниже 10%. Включен таймер дератирования (ограничения крутящего момента двигателя на 25% через 50 км).",
            probableCauses = listOf(
                "Физическое отсутствие реагента AdBlue (DEF) в баке",
                "Зависание поплавкового герконового датчика уровня в баке мочевины",
                "Кристаллизация мочевины на сетчатом заборнике насосного модуля",
                "Загрязнение электрического разъема комбинированного датчика уровня и температуры"
            ),
            severity = DtcSeverity.CRITICAL,
            isActive = true,
            freezeFrame = mapOf(
                "Уровень AdBlue" to "7 %",
                "Температура реагента" to "18 °C",
                "Давление дозирования" to "6.2 бар",
                "Ограничение момента" to "Активно (через 35 км)"
            )
        ),
        DtcCode(
            id = "DTC_03",
            spnFmi = "SPN 3226 FMI 9",
            obdCode = "U029E",
            module = TruckModule.SCR,
            title = "Датчик NOx за катализатором — потеря CAN-связи",
            description = "Отсутствуют периодические сообщения от интеллектуального датчика оксидов азота за глушителем-катализатором по шине CAN 250k.",
            probableCauses = listOf(
                "Обрыв линии CAN-H или CAN-L в жгуте рамы грузовика",
                "Перегорание предохранителя питания подогрева датчика NOx (F14/15A)",
                "Попадание влаги или дорожных реагентов в плату контроллера датчика",
                "Неисправность самого датчика NOx (Continental / Sinotruk)"
            ),
            severity = DtcSeverity.WARNING,
            isActive = true,
            freezeFrame = mapOf(
                "Выбросы NOx" to "Нет данных",
                "Температура глушителя" to "320 °C",
                "Статус подогрева" to "Таймаут"
            )
        ),
        DtcCode(
            id = "DTC_04",
            spnFmi = "SPN 1087 FMI 1",
            obdCode = "SPN 1087",
            module = TruckModule.EBS,
            title = "Давление в контуре 1 рабочей тормозной системы ниже нормы",
            description = "Пневматическое давление в ресивере контура 1 упало ниже 6.5 бар при движении.",
            probableCauses = listOf(
                "Утечка воздуха в соединительных пневмотрубках или быстросъемах",
                "Залипание клапана четырехконтурного защитного блока (APU)",
                "Износ или снижение производительности поршневой группы компрессора",
                "Неисправность аналогового датчика давления контура 1"
            ),
            severity = DtcSeverity.CRITICAL,
            isActive = true,
            freezeFrame = mapOf(
                "Контур 1" to "5.8 бар",
                "Контур 2" to "8.4 бар",
                "Стояночный тормоз" to "Выключен"
            )
        )
    )

    val allKnownSitrakCodes: List<DtcCode> = sampleActiveFaults + listOf(
        DtcCode(
            id = "DTC_05",
            spnFmi = "SPN 157 FMI 1",
            obdCode = "P0087",
            module = TruckModule.ECM,
            title = "Давление в топливной рампе Common Rail ниже уставки",
            description = "Фактическое давление в гидроаккумуляторе топлива ниже заданного значения более чем на 250 бар в течение 3 секунд.",
            probableCauses = listOf(
                "Засорение топливного фильтра тонкой очистки или сепаратора с подогревом",
                "Слив топлива в обратную магистраль через клапан ограничения давления (PRV)",
                "Износ мультипликаторных клапанов форсунок Common Rail (высокий слив в обратку)",
                "Недостаточная подача ТНВД CP3.4+ или неисправность дозирующего клапана MeUN"
            ),
            severity = DtcSeverity.CRITICAL,
            isActive = false,
            freezeFrame = mapOf(
                "Давление заданное" to "1600 бар",
                "Давление факт" to "1280 бар",
                "Обороты" to "1650 об/мин"
            )
        ),
        DtcCode(
            id = "DTC_06",
            spnFmi = "SPN 3719 FMI 16",
            obdCode = "P2463",
            module = TruckModule.SCR,
            title = "Сажевый фильтр DPF — критическое накопление сажи",
            description = "Расчетная степень заполнения сажевого фильтра превысила 85%. Требуется проведение сервисной статической регенерации.",
            probableCauses = listOf(
                "Частая работа на холостом ходу или езда в заторах без выхода на рабочую температуру",
                "Неисправность датчика дифференциального давления DPF",
                "Попадание моторного масла во впускной тракт через турбокомпрессор",
                "Недогрев выхлопных газов из-за заклинившего термостата"
            ),
            severity = DtcSeverity.WARNING,
            isActive = false,
            freezeFrame = mapOf(
                "Заполнение DPF" to "89 %",
                "Дифференциальное давление" to "18 кПа",
                "Пробег с последней регенерации" to "1420 км"
            )
        ),
        DtcCode(
            id = "DTC_07",
            spnFmi = "SPN 523 FMI 2",
            obdCode = "P0700",
            module = TruckModule.TCU,
            title = "КПП ZF TraXon — недостоверный сигнал включения передачи",
            description = "Блок управления автоматизированной коробкой передач зафиксировал расхождение между положением штока переключения и датчиком нейтрали.",
            probableCauses = listOf(
                "Низкое давление пневмосистемы управления приводом выбора передач",
                "Загрязнение датчика Холла положения цилиндра переключения",
                "Износ синхронизаторов или вилки включения делителя/демультипликатора",
                "Потеря связи CAN между двигателем и блоком TraXon"
            ),
            severity = DtcSeverity.WARNING,
            isActive = false,
            freezeFrame = mapOf(
                "Передача целевая" to "9",
                "Передача факт" to "Нейтраль",
                "Давление воздуха в КПП" to "7.1 бар"
            )
        ),
        DtcCode(
            id = "DTC_08",
            spnFmi = "SPN 190 FMI 0",
            obdCode = "P0219",
            module = TruckModule.ECM,
            title = "Превышение максимально допустимых оборотов двигателя",
            description = "Зафиксирован разгон коленчатого вала двигателя выше 2500 об/мин (ошибка при торможении двигателем EVB на затяжном спуске).",
            probableCauses = listOf(
                "Ошибочное переключение на пониженную передачу на высокой скорости движения",
                "Неисправность датчика положения коленчатого вала (дребезг импульсов)",
                "Разнос двигателя из-за попадания масла из турбины"
            ),
            severity = DtcSeverity.CRITICAL,
            isActive = false,
            freezeFrame = mapOf(
                "Пиковые обороты" to "2620 об/мин",
                "Скорость авто" to "98 км/ч"
            )
        ),
        DtcCode(
            id = "DTC_09",
            spnFmi = "SPN 520215 FMI 7",
            obdCode = "B1002",
            module = TruckModule.CBCU,
            title = "Блок CBCU (кузовная электроника) — несоответствие статуса реле",
            description = "Координатор кабины зафиксировал залипание силового реле вспомогательных потребителей Кл.15.",
            probableCauses = listOf(
                "Подгорание контактной группы реле R3 в блоке предохранителей кабины",
                "Нештатное подключение рации, навигатора или холодильника к цепи зажигания",
                "Попадание конденсата в распределительную панель"
            ),
            severity = DtcSeverity.INFO,
            isActive = false
        ),
        DtcCode(
            id = "DTC_10",
            spnFmi = "SPN 1807 FMI 13",
            obdCode = "C10AC",
            module = TruckModule.EBS,
            title = "Датчик угла поворота руля (SAS) — требуется калибровка нуля",
            description = "Блок WABCO EBS/ESP зафиксировал рассогласование нулевой точки датчика угла поворота рулевого колеса SAS (Steering Angle Sensor) после сход-развала или сервисного обслуживания.",
            probableCauses = listOf(
                "Регулировка углов установки колес (сход-развал) без последующей адаптации SAS",
                "Замена рулевой тяги, наконечников или рулевого механизма гидроусилителя",
                "Демонтаж рулевого колеса или подрулевого спирального кабеля",
                "Временный сбой адаптаций ESP после глубокого разряда АКБ 24В",
                "Решение: выполнить калибровку нуля в разделе «Калибровки» (Routine 31 01 02 01)"
            ),
            severity = DtcSeverity.WARNING,
            isActive = false,
            freezeFrame = mapOf(
                "Угол руля (факт)" to "-4.2°",
                "Статус калибровки" to "Сброшена",
                "Скорость авто" to "0 км/ч",
                "Стояночный тормоз" to "Включен"
            )
        )
    )

    fun matchOrSynthesizeCode(codeStr: String, module: TruckModule, extraInfo: String? = null): DtcCode {
        val clean = codeStr.trim().uppercase(java.util.Locale.ROOT)
        val found = allKnownSitrakCodes.firstOrNull {
            it.obdCode.equals(clean, ignoreCase = true) ||
            it.spnFmi.equals(clean, ignoreCase = true)
        }
        if (found != null) return found.copy(
            id = "DTC_${module.code}_${found.obdCode}",
            module = module,
            isActive = true
        )

        val isSpn = clean.startsWith("SPN ")
        val prefix = when {
            isSpn -> clean
            clean.startsWith("P") || clean.startsWith("C") || clean.startsWith("B") || clean.startsWith("U") -> clean
            else -> "P$clean"
        }

        val infoSuffix = if (!extraInfo.isNullOrBlank()) " ($extraInfo)" else ""
        return DtcCode(
            id = "DTC_${module.code}_${prefix.replace(" ", "_")}",
            spnFmi = if (isSpn) prefix else "Код $prefix",
            obdCode = prefix,
            module = module,
            title = "Код неисправности $prefix (${module.code})$infoSuffix",
            description = "Зафиксирован активный код ошибки в блоке ${module.displayName}$infoSuffix. Считан по диагностической шине CAN Sitrak.",
            probableCauses = listOf(
                "Недостоверный сигнал датчика или исполнительного механизма",
                "Обрыв, окисление контактов или замыкание сигнальной цепи",
                "Нарушение калибровки или потеря обмена по шине CAN"
            ),
            severity = if (prefix.startsWith("P02") || prefix.startsWith("P00") || prefix.startsWith("P20") || module == TruckModule.EBS) {
                DtcSeverity.CRITICAL
            } else {
                DtcSeverity.WARNING
            },
            isActive = true
        )
    }

    /**
     * Parses standard OBD Mode 03 (43), KWP2000 (58), UDS 19 02/0A (59) responses into DtcCode list.
     */
    fun parseDtcResponse(raw: String, module: TruckModule): List<DtcCode> {
        val clean = raw.replace(">", " ").replace("\r", "\n").trim()
        if (clean.isEmpty() || clean.contains("NO DATA", ignoreCase = true) ||
            clean.contains("UNABLE TO CONNECT", ignoreCase = true) ||
            clean.contains("CAN ERROR", ignoreCase = true) ||
            clean.contains("?", ignoreCase = true)
        ) {
            return emptyList()
        }

        // Remove ISO-TP multi-frame line prefixes like "0:", "1:", "2:" and standalone length headers like "00E"
        val normalizedLines = clean.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.contains("SEARCHING", ignoreCase = true) ||
                trimmed.contains("STOPPED", ignoreCase = true) || trimmed.equals("OK", ignoreCase = true)
            ) {
                null
            } else {
                val withoutFrameIdx = trimmed.replace(Regex("""^[0-9A-Fa-f]{1,2}:\s*"""), "")
                // Skip standalone 3-hex-char ISO-TP total length lines (e.g. "00E", "014")
                if (withoutFrameIdx.matches(Regex("""^[0-9A-Fa-f]{3}$"""))) null else withoutFrameIdx
            }
        }

        val joined = normalizedLines.joinToString(" ")
        val hexTokens = joined.split(Regex("""\s+""")).filter {
            it.length == 2 && it.matches(Regex("[0-9A-Fa-f]{2}"))
        }
        if (hexTokens.isEmpty()) return emptyList()

        val results = mutableListOf<DtcCode>()

        // 1. Check UDS 19 02 / 19 0A response: 59 [02|0A] [AvailMask] [B1] [B2] [FTB] [Status] (4 bytes per DTC!)
        val idx59 = hexTokens.indexOfFirst { it.equals("59", ignoreCase = true) }
        if (idx59 != -1 && idx59 + 2 < hexTokens.size) {
            val subFunc = hexTokens[idx59 + 1].uppercase(java.util.Locale.ROOT)
            if (subFunc == "02" || subFunc == "0A" || subFunc == "04" || subFunc == "06") {
                var i = idx59 + 3 // skip 59, subfunction, availability mask
                while (i + 3 < hexTokens.size) {
                    val b1 = hexTokens[i].uppercase(java.util.Locale.ROOT)
                    val b2 = hexTokens[i + 1].uppercase(java.util.Locale.ROOT)
                    val b3 = hexTokens[i + 2].uppercase(java.util.Locale.ROOT) // FTB
                    val status = hexTokens[i + 3].uppercase(java.util.Locale.ROOT)
                    val isPadding = (b1 == "00" && b2 == "00" && b3 == "00") || (b1 == "FF" && b2 == "FF")
                    if (!isPadding) {
                        val codeStr = parseObdDtc(b1, b2)
                        val extra = "UDS $b1$b2-$b3, статус 0x$status"
                        val dtc = matchOrSynthesizeCode(codeStr, module, extra)
                        if (results.none { it.obdCode == dtc.obdCode && it.module == dtc.module }) {
                            results.add(dtc)
                        }
                    }
                    i += 4
                }
                if (results.isNotEmpty()) return results
            }
        }

        // 2. Check KWP2000 18 00 FF 00 / 18 02 FF 00 response: 58 [Count] [B1] [B2] [Status] (3 bytes per DTC)
        val idx58 = hexTokens.indexOfFirst { it.equals("58", ignoreCase = true) }
        if (idx58 != -1 && idx58 + 1 < hexTokens.size) {
            val count = hexTokens[idx58 + 1].toIntOrNull(16) ?: 0
            if (count > 0) {
                var i = idx58 + 2
                var parsed = 0
                while (i + 2 < hexTokens.size && parsed < count) {
                    val b1 = hexTokens[i].uppercase(java.util.Locale.ROOT)
                    val b2 = hexTokens[i + 1].uppercase(java.util.Locale.ROOT)
                    val status = hexTokens[i + 2].uppercase(java.util.Locale.ROOT)
                    if (b1 != "00" || b2 != "00") {
                        val codeStr = parseObdDtc(b1, b2)
                        val dtc = matchOrSynthesizeCode(codeStr, module, "KWP $b1$b2, статус 0x$status")
                        if (results.none { it.obdCode == dtc.obdCode && it.module == dtc.module }) {
                            results.add(dtc)
                        }
                    }
                    i += 3
                    parsed++
                }
                if (results.isNotEmpty()) return results
            }
        }

        // 3. Check OBD Mode 03 response (43 [count?] [B1] [B2] ...)
        val idx43 = hexTokens.indexOfFirst { it.equals("43", ignoreCase = true) }
        if (idx43 != -1 && idx43 + 2 < hexTokens.size) {
            val remaining = hexTokens.size - (idx43 + 1)
            // ISO 15765-4 uses 43 [count] [b1 b2]..., legacy uses 43 [b1 b2]...
            val firstAfter43 = hexTokens[idx43 + 1].toIntOrNull(16) ?: -1
            val hasCountByte = (remaining % 2 == 1) || (firstAfter43 in 1..20 && remaining >= 1 + firstAfter43 * 2)
            var i = if (hasCountByte) idx43 + 2 else idx43 + 1
            while (i + 1 < hexTokens.size) {
                val b1 = hexTokens[i].uppercase(java.util.Locale.ROOT)
                val b2 = hexTokens[i + 1].uppercase(java.util.Locale.ROOT)
                if (b1 != "00" || b2 != "00") {
                    val codeStr = parseObdDtc(b1, b2)
                    val dtc = matchOrSynthesizeCode(codeStr, module)
                    if (results.none { it.obdCode == dtc.obdCode && it.module == dtc.module }) {
                        results.add(dtc)
                    }
                }
                i += 2
            }
        }

        return results
    }

    /**
     * Parses SAE J1939 DM1 (PGN FECA) and DM2 (PGN FECB) responses (both ATSPA reassembled and raw CAN TP BAM frames).
     */
    fun parseJ1939DmResponse(raw: String, fallbackModule: TruckModule = TruckModule.ECM): List<DtcCode> {
        val clean = raw.replace(">", "\n").replace("\r", "\n").trim()
        if (clean.isEmpty()) return emptyList()

        val results = mutableListOf<DtcCode>()
        val bamBuffers = mutableMapOf<Int, MutableList<Int>>() // SA -> reassembled DM payload bytes

        val lines = clean.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var currentHeaderSa: Int? = null
        var currentIsDm = false

        for (line in lines) {
            val upper = line.uppercase(java.util.Locale.ROOT)
            if (upper.contains("NO DATA") || upper.contains("SEARCHING") || upper.contains("STOPPED") ||
                upper.contains("ERROR") || upper == "OK" || upper.contains("?")
            ) {
                continue
            }

            // Case A: Reassembled ATSPA multi-line block header e.g. "18 FECA 0B" or "18FECA0B"
            val hdrOnlyMatch = Regex("""^([0-9A-F]{2})\s*(FE(?:CA|CB))\s*([0-9A-F]{2})$""").find(upper)
            if (hdrOnlyMatch != null) {
                currentHeaderSa = hdrOnlyMatch.groupValues[3].toIntOrNull(16)
                currentIsDm = true
                bamBuffers.getOrPut(currentHeaderSa!!) { mutableListOf() }.clear()
                continue
            }

            // Skip standalone 3-char multi-packet byte count line (e.g. "00E") without breaking DM state
            if (currentIsDm && upper.matches(Regex("""^[0-9A-F]{3}$"""))) {
                continue
            }

            // Case B: Multi-line frame payload line "0: 04 FF 66 00 03 01 ..."
            val frameLineMatch = Regex("""^[0-9A-F]{1,2}:\s*(.+)$""").find(upper)
            if (frameLineMatch != null && currentIsDm && currentHeaderSa != null) {
                val bytes = frameLineMatch.groupValues[1].trim().split(Regex("""\s+"""))
                    .mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
                bamBuffers.getOrPut(currentHeaderSa!!) { mutableListOf() }.addAll(bytes)
                continue
            }

            // Case C: Full CAN frame on one line:
            //   1) "18FECA0B 04 FF ..." (8-char header)
            //   2) "18 FECA 0B 04 FF ..." (2-4-2 ELM ATSPA header)
            //   3) "18 FE CA 0B 04 FF ..." (2-2-2-2 raw CAN header)
            val tokens = upper.split(Regex("""\s+"""))
            val mergedHeaderMatch = Regex("""^([0-9A-F]{2})([0-9A-F]{4})([0-9A-F]{2})$""").find(tokens.firstOrNull() ?: "")
            val (pgnHex, saInt, dataBytes) = if (mergedHeaderMatch != null) {
                val pgn = mergedHeaderMatch.groupValues[2]
                val sa = mergedHeaderMatch.groupValues[3].toIntOrNull(16) ?: continue
                val data = tokens.drop(1).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
                Triple(pgn, sa, data)
            } else if (tokens.size >= 5 && tokens[0].length == 2 && tokens[1].length == 4 && tokens[2].length == 2) {
                val pgn = tokens[1]
                val sa = tokens[2].toIntOrNull(16) ?: continue
                val data = tokens.drop(3).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
                Triple(pgn, sa, data)
            } else if (tokens.size >= 6 && tokens[0].length == 2 && tokens[1].length == 2 && tokens[2].length == 2 && tokens[3].length == 2) {
                val pgn = tokens[1] + tokens[2]
                val sa = tokens[3].toIntOrNull(16) ?: continue
                val data = tokens.drop(4).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
                Triple(pgn, sa, data)
            } else {
                currentIsDm = false
                continue
            }

            currentIsDm = false
            when (pgnHex) {
                "FECA", "FECB" -> {
                    decodeJ1939DmPayload(dataBytes, mapJ1939SaToModule(saInt, fallbackModule), results)
                }
                "ECFF" -> {
                    // BAM announce: byte0=0x20, byte5..7 = PGN LSB..MSB (CA FE 00 for FECA, CB FE 00 for FECB)
                    if (dataBytes.size >= 8 && dataBytes[0] == 0x20 &&
                        (dataBytes[5] == 0xCA || dataBytes[5] == 0xCB) && dataBytes[6] == 0xFE
                    ) {
                        bamBuffers[saInt] = mutableListOf()
                    }
                }
                "EBFF" -> {
                    // TP.DT packet: byte0 = sequence number (1..255), bytes 1..7 = payload
                    val buf = bamBuffers[saInt]
                    if (buf != null && dataBytes.size >= 2) {
                        buf.addAll(dataBytes.drop(1))
                    }
                }
            }
        }

        for ((saInt, payload) in bamBuffers) {
            if (payload.size >= 6) {
                decodeJ1939DmPayload(payload, mapJ1939SaToModule(saInt, fallbackModule), results)
            }
        }

        return results
    }

    private fun decodeJ1939DmPayload(bytes: List<Int>, module: TruckModule, out: MutableList<DtcCode>) {
        // DM1/DM2: Byte 0 = Lamp Status, Byte 1 = Flash Status, then 4-byte DTC blocks
        if (bytes.size < 6) return
        var i = 2
        while (i + 3 < bytes.size) {
            val b0 = bytes[i]
            val b1 = bytes[i + 1]
            val b2 = bytes[i + 2]
            val b3 = bytes[i + 3]

            // Skip empty / NA slots (00 00 00 00 or FF FF FF FF)
            val allZero = b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0
            val allFf = b0 == 0xFF && b1 == 0xFF && b2 == 0xFF && b3 == 0xFF
            if (!allZero && !allFf) {
                val spn = b0 or (b1 shl 8) or ((b2 and 0xE0) shl 11)
                val fmi = b2 and 0x1F
                val oc = b3 and 0x7F
                if (spn in 1..524286) {
                    val spnStr = "SPN $spn FMI $fmi"
                    val fmiDesc = describeFmi(fmi)
                    val dtc = matchOrSynthesizeCode(spnStr, module, "$fmiDesc, повт.: $oc")
                    if (out.none { it.spnFmi == dtc.spnFmi && it.module == dtc.module }) {
                        out.add(dtc)
                    }
                }
            }
            i += 4
        }
    }

    private fun mapJ1939SaToModule(sa: Int, fallback: TruckModule): TruckModule {
        return when (sa) {
            0x00 -> TruckModule.ECM
            0x03 -> TruckModule.TCU
            0x0B -> TruckModule.EBS
            0x3D, 0x55 -> TruckModule.SCR
            0x21, 0x27, 0x17 -> TruckModule.CBCU
            else -> fallback
        }
    }

    private fun describeFmi(fmi: Int): String = when (fmi) {
        0 -> "Данные выше нормы (крит.)"
        1 -> "Данные ниже нормы (крит.)"
        2 -> "Недостоверный / прерывистый сигнал"
        3 -> "Замыкание на +24В / высокое напряжение"
        4 -> "Замыкание на массу / низкое напряжение"
        5 -> "Обрыв цепи / высокий импеданс"
        6 -> "Повышенный ток / КЗ на массу"
        7 -> "Механическая неисправность"
        8 -> "Нарушение частоты / ШИМ"
        9 -> "Таймаут обмена по шине CAN"
        10 -> "Аномальное изменение сигнала"
        11 -> "Неизвестная причина неисправности"
        12 -> "Внутренний дефект блока / датчика"
        13 -> "Требуется калибровка (вне диапазона)"
        14 -> "Специальные условия неисправности"
        15 -> "Превышение верхнего предела (предупр.)"
        16 -> "Умеренное превышение верхнего порога"
        17 -> "Снижение ниже нижнего порога"
        18 -> "Умеренное снижение ниже порога"
        19 -> "Получены ошибочные данные по CAN"
        31 -> "Состояние ошибки активно"
        else -> "FMI $fmi"
    }

    private fun parseObdDtc(b1Hex: String, b2Hex: String): String {
        return try {
            val b1 = b1Hex.toInt(16)
            val type = when ((b1 shr 6) and 0x03) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                3 -> "U"
                else -> "P"
            }
            val d1 = (b1 shr 4) and 0x03
            val d2 = b1 and 0x0F
            val d3 = b2Hex.take(1)
            val d4 = b2Hex.takeLast(1)
            "$type$d1$d2$d3$d4".uppercase(java.util.Locale.ROOT)
        } catch (_: Exception) {
            "P$b1Hex$b2Hex"
        }
    }
}
