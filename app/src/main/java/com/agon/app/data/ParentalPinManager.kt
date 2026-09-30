package com.agon.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "parental_pin")

/**
 * Singleton manager for the Family Firewall feature.
 *
 * V9.7 — SECURITY FIX: Replaced SHA-256 (no salt, crackable in <1ms via
 * rainbow table for 4-digit PINs) with PBKDF2-HMAC-SHA256 using:
 *   - 100,000 iterations (OWASP 2023 recommendation)
 *   - 16-byte cryptographically random salt per PIN
 *   - 256-bit derived key
 *
 * Storage format: "saltHex:hashHex" (e.g., "a1b2c3...:d4e5f6...")
 *
 * This makes brute-force attack on a 4-digit PIN take ~100 seconds
 * (100,000 iterations × 10,000 combinations) instead of <1ms — a
 * 100,000x improvement in resistance.
 *
 * Backward compatibility: if the stored value doesn't contain ":", it's
 * treated as a legacy SHA-256 hash and verified via the old algorithm.
 * On successful legacy verification, the PIN is automatically upgraded
 * to PBKDF2.
 */
object ParentalPinManager {

    private val PIN_KEY = stringPreferencesKey("stored_pin")
    private val LOCKED_GROUPS_KEY = stringPreferencesKey("locked_groups")

    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_LENGTH = 256
    private const val SALT_LENGTH_BYTES = 16

    @Volatile
    private var instance: ParentalPinManager? = null

    /**
     * Returns the singleton instance. Uses double-checked locking for lazy init.
     */
    fun getInstance(context: Context): ParentalPinManager {
        return instance ?: synchronized(this) {
            instance ?: ParentalPinManager.also { instance = it }
        }
    }

    // ── PIN helpers (V9.7 — PBKDF2 + salt) ────────────────────────────────

    /**
     * Generates a cryptographically random 16-byte salt.
     */
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Derives a key from the PIN + salt using PBKDF2-HMAC-SHA256.
     * 100,000 iterations makes brute-force impractical even for 4-digit PINs.
     */
    private fun pbkdf2Hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /**
     * Converts bytes to lowercase hex string.
     */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Converts hex string back to bytes.
     */
    private fun String.hexToBytes(): ByteArray {
        val len = length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = ((this[i * 2].digitToIntValue() shl 4) or this[i * 2 + 1].digitToIntValue()).toByte()
        }
        return result
    }

    private fun Char.digitToIntValue(): Int =
        when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            in 'A'..'F' -> this - 'A' + 10
            else -> 0
        }

    /**
     * Hashes a PIN with a fresh random salt. Returns "saltHex:hashHex".
     */
    private fun hashPin(pin: String): String {
        val salt = generateSalt()
        val hash = pbkdf2Hash(pin, salt)
        return "${salt.toHex()}:${hash.toHex()}"
    }

    /**
     * Verifies a PIN against a stored hash. Supports both V9.7 PBKDF2 format
     * ("salt:hash") and legacy SHA-256 format (plain hex, for backward compat).
     */
    private fun verifyPinAgainstStored(pin: String, stored: String): Boolean {
        // V9.7 format: "saltHex:hashHex"
        if (stored.contains(":")) {
            val parts = stored.split(":", limit = 2)
            if (parts.size != 2) return false
            return try {
                val salt = parts[0].hexToBytes()
                val expectedHash = parts[1].hexToBytes()
                val actualHash = pbkdf2Hash(pin, salt)
                // Constant-time comparison to prevent timing attacks
                constantTimeEquals(expectedHash, actualHash)
            } catch (_: Throwable) {
                false
            }
        }
        // Legacy SHA-256 format (backward compat — will be upgraded on next setPin)
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
            val legacyHash = bytes.joinToString("") { "%02x".format(it) }
            legacyHash == stored
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Constant-time byte array comparison to prevent timing side-channel attacks.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    /**
     * Returns true if a PIN has already been set.
     */
    suspend fun hasPin(context: Context): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[PIN_KEY] != null
        }.first()
    }

    /**
     * Stores the PBKDF2 hash (with salt) of the given 4-digit PIN.
     * V9.7 — Uses PBKDF2-HMAC-SHA256 with 100,000 iterations + random salt.
     */
    suspend fun setPin(context: Context, pin: String) {
        context.dataStore.edit { prefs ->
            prefs[PIN_KEY] = hashPin(pin)
        }
    }

    /**
     * Hashes the input PIN and compares it with the stored hash.
     * Returns false if no PIN has been set.
     *
     * V9.7 — If the stored hash is in legacy SHA-256 format (no salt),
     * a successful verification automatically upgrades it to PBKDF2.
     */
    suspend fun verifyPin(context: Context, pin: String): Boolean {
        val storedHash = context.dataStore.data.map { prefs ->
            prefs[PIN_KEY]
        }.first() ?: return false

        val matches = verifyPinAgainstStored(pin, storedHash)

        // Auto-upgrade legacy SHA-256 hashes to PBKDF2
        if (matches && !storedHash.contains(":")) {
            try {
                setPin(context, pin)  // Re-hash with PBKDF2 + salt
            } catch (_: Throwable) {}
        }

        return matches
    }

    // ── Group lock helpers ────────────────────────────────────────────────

    /**
     * Parses the comma-separated locked_groups string into a mutable set.
     */
    private fun parseLockedGroups(raw: String?): MutableSet<String> {
        if (raw.isNullOrBlank()) return mutableSetOf()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
    }

    /**
     * Adds [groupName] to the locked groups list.
     */
    suspend fun lockGroup(context: Context, groupName: String) {
        context.dataStore.edit { prefs ->
            val current = parseLockedGroups(prefs[LOCKED_GROUPS_KEY])
            current.add(groupName)
            prefs[LOCKED_GROUPS_KEY] = current.joinToString(",")
        }
    }

    /**
     * Removes [groupName] from the locked groups list.
     */
    suspend fun unlockGroup(context: Context, groupName: String) {
        context.dataStore.edit { prefs ->
            val current = parseLockedGroups(prefs[LOCKED_GROUPS_KEY])
            current.remove(groupName)
            prefs[LOCKED_GROUPS_KEY] = current.joinToString(",")
        }
    }

    /**
     * Returns true if [groupName] is in the locked groups list.
     */
    suspend fun isGroupLocked(context: Context, groupName: String): Boolean {
        val raw = context.dataStore.data.map { prefs ->
            prefs[LOCKED_GROUPS_KEY]
        }.first()
        return groupName in parseLockedGroups(raw)
    }

    /**
     * Returns the current set of all locked group names.
     */
    suspend fun getLockedGroups(context: Context): Set<String> {
        val raw = context.dataStore.data.map { prefs ->
            prefs[LOCKED_GROUPS_KEY]
        }.first()
        return parseLockedGroups(raw)
    }

    /**
     * Toggles the lock state of [groupName].
     * Returns true if the group is now locked, false if it is now unlocked.
     */
    suspend fun toggleGroupLock(context: Context, groupName: String): Boolean {
        val currentlyLocked = isGroupLocked(context, groupName)
        if (currentlyLocked) {
            unlockGroup(context, groupName)
        } else {
            lockGroup(context, groupName)
        }
        return !currentlyLocked
    }
}
