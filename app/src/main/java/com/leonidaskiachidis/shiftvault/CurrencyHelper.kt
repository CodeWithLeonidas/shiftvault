package com.leonidaskiachidis.shiftvault

import java.util.Currency
import java.util.Locale

object CurrencyHelper {
    fun getAllCurrencies(): List<CurrencyInfo> {
        val popularCodes = listOf("EUR", "USD", "GBP", "CHF", "JPY")

        val allCurrencies = Locale.getAvailableLocales()
            .mapNotNull { locale ->
                try {
                    val currency = java.util.Currency.getInstance(locale)
                    CurrencyInfo(
                        symbol = currency.symbol,
                        code = currency.currencyCode,
                        name = currency.getDisplayName(Locale.getDefault())
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.code }

        // Split into two lists: popular and the rest
        val popular = allCurrencies.filter { it.code in popularCodes }
            .sortedBy { popularCodes.indexOf(it.code) } // Keep the order of your list

        val others = allCurrencies.filter { it.code !in popularCodes }
            .sortedBy { it.name } // Alphabetical for the rest

        return popular + others
    }
}