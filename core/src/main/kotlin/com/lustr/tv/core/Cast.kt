package com.lustr.tv.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Labels as in the web app. */
fun genderLabel(gender: String?): String = when (gender) {
    "female" -> "Female"
    "male" -> "Male"
    "trans_female" -> "Trans woman"
    "trans_male" -> "Trans man"
    null, "" -> ""
    else -> pretty(gender)
}

fun categoryLabel(category: String?): String = when (category) {
    "pro" -> "Pro"
    "amateur" -> "Amateur"
    "celeb" -> "Celeb"
    null, "" -> ""
    else -> pretty(category)
}

/** "BLUE_GREEN" / "blue_green" -> "Blue green" (the web app's extDbPretty). */
fun pretty(value: String): String =
    value.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** Sorting the cast browser. */
enum class CastSort(val label: String) { MOST_VIDEOS("Most videos"), NAME("Name") }

/** Which genders the cast browser shows. */
enum class CastGenderFilter(val label: String, val genders: Set<String>?) {
    ALL("Everyone", null),
    FEMALE("Women", setOf("female")),
    MALE("Men", setOf("male")),
    TRANS("Trans", setOf("trans_female", "trans_male")),
}

/** Pro / Amateur / Celeb, like the web app's cast browser (no category = pro). */
enum class CastCategoryFilter(val label: String, val category: String?) {
    ALL("All types", null),
    PRO("Pro", "pro"),
    AMATEUR("Amateur", "amateur"),
    CELEB("Celeb", "celeb"),
}

/** The cast browser's search / filters, applied on the TV (the server sends everyone at once). */
object CastBrowse {
    fun filter(
        cast: List<CastMember>,
        query: String = "",
        favoritesOnly: Boolean = false,
        gender: CastGenderFilter = CastGenderFilter.ALL,
        sort: CastSort = CastSort.MOST_VIDEOS,
        category: CastCategoryFilter = CastCategoryFilter.ALL,
    ): List<CastMember> {
        val q = query.trim().lowercase()
        val shown = cast.filter { c ->
            (q.isEmpty() || q in c.name.lowercase() || c.aliases.any { q in it.lowercase() }) &&
                (!favoritesOnly || c.isFavorite) &&
                (gender.genders == null || (c.gender ?: "female") in gender.genders) &&
                (category.category == null || (c.category ?: "pro") == category.category)
        }
        return when (sort) {
            CastSort.MOST_VIDEOS -> shown.sortedWith(compareByDescending<CastMember> { it.count }.thenBy { it.name.lowercase() })
            CastSort.NAME -> shown.sortedBy { it.name.lowercase() }
        }
    }
}

/** The external profile (StashDB / ThePornDB) as label -> value rows, like the web app's bio "Profile". */
object ExtProfile {
    fun rows(link: JsonObject?): List<Pair<String, String>> {
        if (link == null) return emptyList()
        val d = link["display"] as? JsonObject ?: JsonObject(emptyMap())

        fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun list(e: JsonElement?): String? = (e as? JsonArray)?.mapNotNull { text(it) }?.joinToString(", ")?.takeIf { it.isNotBlank() }
        fun mods(e: JsonElement?): String? = (e as? JsonArray)?.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val loc = text(o["location"]) ?: return@mapNotNull null
            text(o["description"])?.let { "$loc ($it)" } ?: loc
        }?.joinToString("; ")?.takeIf { it.isNotBlank() }

        val birthday = text(d["birthdate"])?.let { date ->
            val age = (d["age"] as? JsonPrimitive)?.intOrNull
            val dead = (d["deceased"] as? JsonPrimitive)?.booleanOrNull == true
            val approx = (d["age_is_approx"] as? JsonPrimitive)?.booleanOrNull == true
            if (age != null) "$date (${if (dead) "died at " else "age "}${if (approx) "≈" else ""}$age)" else date
        }
        val years = (d["years_active"] as? JsonObject)?.let { ya -> text(ya["text"]) }
        val name = text(link["ext_name"])?.let { n -> text(link["ext_disambiguation"])?.let { "$n ($it)" } ?: n }

        return listOf(
            "Name" to name,
            "Aliases" to list(d["aliases"]),
            "Gender" to text(link["ext_gender"])?.let(::pretty),
            "Birthday" to birthday,
            "Died" to text(link["ext_deathdate"]),
            "Years active" to years,
            "Ethnicity" to text(d["ethnicity"]),
            "Nationality" to text(d["nationality"]),
            "Height" to text(d["height"]),
            "Measurements" to text(d["measurements"]),
            "Cup size" to text(d["cup_size"]),
            "Breast type" to text(d["breast_type"]),
            "Eye color" to text(link["ext_eye_color"])?.let(::pretty),
            "Hair color" to text(link["ext_hair_color"])?.let(::pretty),
            "Tattoos" to mods(link["ext_tattoos"]),
            "Piercings" to mods(link["ext_piercings"]),
            "Scenes" to text(link["ext_scene_count"]),
        ).mapNotNull { (label, value) -> value?.let { label to it } }
    }
}

/** Sorts of the Movies tab (server names). */
enum class MovieSort(val value: String, val label: String) {
    TITLE("title", "Title"),
    YEAR("year", "Year (newest first)"),
    SCENES("count", "Most scenes"),
    ADDED("added", "Recently added"),
    FAVORITES("favorites", "Favorites first"),
}

/** {"favorite": true} or {"favorite": 1}. */
internal fun favoriteOf(reply: JsonObject): Boolean {
    val p = reply["favorite"]?.jsonPrimitive ?: return false
    return p.booleanOrNull ?: ((p.intOrNull ?: 0) != 0)
}
