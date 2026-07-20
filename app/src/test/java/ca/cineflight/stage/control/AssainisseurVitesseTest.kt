package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de AssainisseurVitesse (ObstacleSafetyGate — ÉTAPE 0).
 *
 * Vérifie le durcissement UNIVERSEL appliqué au dernier point avant le SDK :
 *  - NaN / ±Infinity → 0 ;
 *  - clamp à ±CLAMP_*_MAX ;
 *  - une commande légitime À la borne exacte ressort INCHANGÉE (aucun bridage
 *    des chemins existants, puisque les bornes sont l'enveloppe supérieure des maxima).
 *
 * Voir docs/PLAN_OBSTACLE_SAFETY_GATE_20260718.md §2 et §7.
 */
class AssainisseurVitesseTest {

    private val EPS = 1e-6f

    @Test
    fun commande_saine_dans_les_bornes_ressort_inchangee() {
        val r = AssainisseurVitesse.assainir(1.5f, -0.8f, 0.9f, 20f)
        assertEquals(1.5f, r.pitch, EPS)
        assertEquals(-0.8f, r.roll, EPS)
        assertEquals(0.9f, r.throttle, EPS)
        assertEquals(20f, r.yaw, EPS)
    }

    @Test
    fun commande_a_la_borne_exacte_ressort_inchangee() {
        // Aucune commande légitime ne doit être bridée : à la borne, on ne modifie rien.
        val r = AssainisseurVitesse.assainir(
            AssainisseurVitesse.CLAMP_HORIZ_MAX,
            -AssainisseurVitesse.CLAMP_HORIZ_MAX,
            AssainisseurVitesse.CLAMP_VERT_MAX,
            AssainisseurVitesse.CLAMP_YAW_MAX
        )
        assertEquals(AssainisseurVitesse.CLAMP_HORIZ_MAX, r.pitch, EPS)
        assertEquals(-AssainisseurVitesse.CLAMP_HORIZ_MAX, r.roll, EPS)
        assertEquals(AssainisseurVitesse.CLAMP_VERT_MAX, r.throttle, EPS)
        assertEquals(AssainisseurVitesse.CLAMP_YAW_MAX, r.yaw, EPS)
    }

    @Test
    fun au_dela_de_la_borne_est_ramene_a_la_borne() {
        val r = AssainisseurVitesse.assainir(100f, -100f, 100f, 1000f)
        assertEquals(AssainisseurVitesse.CLAMP_HORIZ_MAX, r.pitch, EPS)
        assertEquals(-AssainisseurVitesse.CLAMP_HORIZ_MAX, r.roll, EPS)
        assertEquals(AssainisseurVitesse.CLAMP_VERT_MAX, r.throttle, EPS)
        assertEquals(AssainisseurVitesse.CLAMP_YAW_MAX, r.yaw, EPS)
        // symétrie négative
        val n = AssainisseurVitesse.assainir(-100f, 100f, -100f, -1000f)
        assertEquals(-AssainisseurVitesse.CLAMP_HORIZ_MAX, n.pitch, EPS)
        assertEquals(-AssainisseurVitesse.CLAMP_VERT_MAX, n.throttle, EPS)
        assertEquals(-AssainisseurVitesse.CLAMP_YAW_MAX, n.yaw, EPS)
    }

    @Test
    fun nan_est_neutralise_a_zero() {
        val r = AssainisseurVitesse.assainir(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        assertEquals(0f, r.pitch, EPS)
        assertEquals(0f, r.roll, EPS)
        assertEquals(0f, r.throttle, EPS)
        assertEquals(0f, r.yaw, EPS)
    }

    @Test
    fun infini_est_neutralise_a_zero() {
        val r = AssainisseurVitesse.assainir(
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        )
        assertEquals(0f, r.pitch, EPS)
        assertEquals(0f, r.roll, EPS)
        assertEquals(0f, r.throttle, EPS)
        assertEquals(0f, r.yaw, EPS)
    }

    @Test
    fun mix_nan_et_valeur_saine_neutralise_seulement_le_nan() {
        val r = AssainisseurVitesse.assainir(1.2f, Float.NaN, -0.5f, Float.POSITIVE_INFINITY)
        assertEquals(1.2f, r.pitch, EPS)
        assertEquals(0f, r.roll, EPS)        // NaN → 0
        assertEquals(-0.5f, r.throttle, EPS)
        assertEquals(0f, r.yaw, EPS)         // ∞ → 0
    }

    @Test
    fun sortie_toujours_finie() {
        val cas = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f, -1e30f, 0f, 3f)
        for (a in cas) for (b in cas) {
            val r = AssainisseurVitesse.assainir(a, b, a, b)
            assertTrue(r.pitch.isFinite() && r.roll.isFinite() && r.throttle.isFinite() && r.yaw.isFinite())
        }
    }

    @Test
    fun bornes_sont_enveloppe_superieure_des_chemins_existants() {
        // Garde-fou : les bornes universelles doivent couvrir tous les maxima légitimes
        // relevés par l'audit. Si un chemin dépasse, il faut relever la borne (sinon on le bride).
        // PiloteDrone ±2.0/±60 ; Phase3 ±4.0 horiz/±2.0 vert/±35 ; PremierVol ≤0.4/≤15.
        assertTrue(AssainisseurVitesse.CLAMP_HORIZ_MAX >= 4.0f)
        assertTrue(AssainisseurVitesse.CLAMP_VERT_MAX  >= 2.0f)
        assertTrue(AssainisseurVitesse.CLAMP_YAW_MAX   >= 60.0f)
    }

    @Test
    fun enum_command_origin_a_les_quatre_valeurs() {
        // UNKNOWN doit exister (défaut non-permissif) et être distinct de MANUAL/AUTOMATIC/TEST.
        val vals = CommandOrigin.values().toSet()
        assertTrue(CommandOrigin.AUTOMATIC in vals)
        assertTrue(CommandOrigin.MANUAL in vals)
        assertTrue(CommandOrigin.TEST in vals)
        assertTrue(CommandOrigin.UNKNOWN in vals)
        assertEquals(4, vals.size)
    }
}
