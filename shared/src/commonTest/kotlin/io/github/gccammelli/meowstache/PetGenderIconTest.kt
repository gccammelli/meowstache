package io.github.gccammelli.meowstache

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Pets
import io.github.gccammelli.meowstache.ui.petGenderIcon
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class PetGenderIconTest {
    @Test fun femaleAndSpayedFemaleUseFemaleSymbol() {
        for (fixed in listOf(false, true)) {
            val pet = Pet(buildJsonObject { put("gender", "FEMALE"); put("isFixed", fixed) })
            assertEquals(if (fixed) "Spayed Female" else "Female", pet.fixedLabel)
            assertEquals(Icons.Filled.Female, petGenderIcon(pet.gender))
        }
    }

    @Test fun maleUsesMaleSymbol() {
        assertEquals(Icons.Filled.Male, petGenderIcon("MALE"))
    }

    @Test fun missingOrUnknownGenderDoesNotImplyMale() {
        for (gender in listOf(null, "", "UNKNOWN")) {
            assertEquals(Icons.Filled.Pets, petGenderIcon(gender))
        }
    }
}
