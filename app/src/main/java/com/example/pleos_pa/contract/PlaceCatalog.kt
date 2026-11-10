package com.example.pleos_pa.contract

import java.util.Locale
import org.json.JSONObject

/**
 * One verified place of `contracts/navigation/places.json`. [poiId] and [poiSubId] are empty unless
 * the stock navigation app gave them; no other provider's ID goes here.
 */
data class Place(
    val key: String,
    val displayName: String,
    val aliases: List<String>,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String,
    val poiSubId: String,
)

/** Names are provisional until the destination tool contract is agreed (COM-21). */
enum class PlaceMatchStatus {
    NO_MATCH,
    SINGLE,
    MULTIPLE,
}

data class PlaceMatch(val candidates: List<Place>) {
    val status: PlaceMatchStatus
        get() =
            when (candidates.size) {
                0 -> PlaceMatchStatus.NO_MATCH
                1 -> PlaceMatchStatus.SINGLE
                else -> PlaceMatchStatus.MULTIPLE
            }
}

/**
 * The fixed destination list for the first release. Only these places may become a destination; a
 * query that matches none of them is never set (`contracts/navigation/CONTRACT.md`).
 *
 * Pure: no SDK, no Android, no logging. The query may come from a calendar entry, so it is never
 * written anywhere.
 */
class PlaceCatalog private constructor(val places: List<Place>) {
    fun place(key: String): Place? = places.firstOrNull { it.key == key }

    /**
     * First-release rule: drop all whitespace and lowercase, then a place is a candidate when the
     * query and its display name or an alias contain one another. No similarity search. Candidates
     * keep list order; no match never falls back to the whole list.
     */
    fun find(query: String): PlaceMatch {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return PlaceMatch(emptyList())
        return PlaceMatch(
            places.filter { place ->
                (listOf(place.displayName) + place.aliases).map(::normalize).any {
                    it.isNotEmpty() && (it.contains(wanted) || wanted.contains(it))
                }
            }
        )
    }

    companion object {
        fun normalize(text: String): String =
            text.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

        fun parse(text: String): PlaceCatalog {
            val array = JSONObject(text).getJSONArray("places")
            val places =
                (0 until array.length()).map {
                    val place = array.getJSONObject(it)
                    val aliases = place.getJSONArray("aliases")
                    Place(
                        key = place.getString("key"),
                        displayName = place.getString("displayName"),
                        aliases = (0 until aliases.length()).map { i -> aliases.getString(i) },
                        address = place.getString("address"),
                        latitude = place.getDouble("latitude"),
                        longitude = place.getDouble("longitude"),
                        poiId = place.getString("poiId"),
                        poiSubId = place.getString("poiSubId"),
                    )
                }
            require(places.map { it.key }.toSet().size == places.size) { "duplicate place key" }
            return PlaceCatalog(places)
        }
    }
}
