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
        )
    )

    fun matchOrSynthesizeCode(codeStr: String, module: TruckModule): DtcCode {
        val clean = codeStr.trim().uppercase(java.util.Locale.ROOT)
        val found = allKnownSitrakCodes.firstOrNull {
            it.obdCode.equals(clean, ignoreCase = true) ||
            it.spnFmi.contains(clean, ignoreCase = true)
        }
        if (found != null) return found.copy(isActive = true)

        val prefix = when {
            clean.startsWith("P") || clean.startsWith("C") || clean.startsWith("B") || clean.startsWith("U") -> clean
            else -> "P$clean"
        }

        return DtcCode(
            id = "DTC_${System.currentTimeMillis()}_$clean",
            spnFmi = "Код $prefix",
            obdCode = prefix,
            module = module,
            title = "Код неисправности $prefix (${module.displayName})",
            description = "Зафиксирован код ошибки в блоке ${module.displayName}. Считан по шине CAN Sitrak.",
            probableCauses = listOf(
                "Недостоверный сигнал датчика или исполнительного механизма",
                "Обрыв или замыкание сигнальной цепи в жгуте проводки",
                "Потеря согласования блоков по цифровой шине CAN J1939"
            ),
            severity = if (prefix.startsWith("P02") || prefix.startsWith("P00") || prefix.startsWith("P20")) DtcSeverity.CRITICAL else DtcSeverity.WARNING,
            isActive = true
        )
    }

    /**
     * Parses standard OBD Mode 03, UDS 19 02 or J1939 responses into DtcCode list.
     */
    fun parseDtcResponse(raw: String, module: TruckModule): List<DtcCode> {
        val clean = raw.replace(">", "").replace("\r", " ").replace("\n", " ").trim()
        if (clean.isEmpty() || clean.contains("NO DATA") || clean.contains("ERROR") || clean == "43 00") {
            return emptyList()
        }

        val results = mutableListOf<DtcCode>()
        val hexTokens = clean.split(Regex("""\s+""")).filter { it.length == 2 && it.matches(Regex("[0-9A-Fa-f]{2}")) }

        // Check if Mode 03 response (starts with 43)
        val idx43 = hexTokens.indexOfFirst { it.equals("43", ignoreCase = true) }
        if (idx43 != -1 && idx43 + 2 < hexTokens.size) {
            // Usually: 43 [count] [byte1] [byte2] ...
            var i = idx43 + 2
            while (i + 1 < hexTokens.size) {
                val b1 = hexTokens[i]
                val b2 = hexTokens[i + 1]
                if (b1 != "00" || b2 != "00") {
                    val codeStr = parseObdDtc(b1, b2)
                    results.add(matchOrSynthesizeCode(codeStr, module))
                }
                i += 2
            }
        }

        // Check if UDS 19 02 response (starts with 59 02)
        val idx59 = hexTokens.indexOfFirst { it.equals("59", ignoreCase = true) }
        if (idx59 != -1 && idx59 + 3 < hexTokens.size) {
            var i = idx59 + 3 // skip 59, subfunction, availability mask
            while (i + 2 < hexTokens.size) {
                val b1 = hexTokens[i]
                val b2 = hexTokens[i + 1]
                // i+2 is failure type / status
                if (b1 != "00" || b2 != "00") {
                    val codeStr = parseObdDtc(b1, b2)
                    results.add(matchOrSynthesizeCode(codeStr, module))
                }
                i += 3
            }
        }

        return results
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
