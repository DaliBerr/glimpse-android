package com.albumclean.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albumclean.app.data.*
import com.albumclean.app.updates.AppUpdater
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PhotoAccess { NONE, PARTIAL, FULL }
data class CleanState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val access: PhotoAccess = PhotoAccess.NONE,
    val photos: List<Photo> = emptyList(),
    val deck: List<String> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val error: String? = null,
) {
    val current: Photo? get() = deck.firstOrNull()?.let { key -> photos.find { it.key == key } }
    val pending: List<Photo> get() = decisions.filter { it.choice == Choice.DELETE }.map { it.photo }
    val kept: Int get() = decisions.count { it.choice == Choice.KEEP }
    val undo: Decision? get() = decisions.lastOrNull { it.choice != Choice.DELETED }
    val busy: Boolean get() = loading || saving || deleting
}

class PhotoViewModel(application: Application) : AndroidViewModel(application) {
    val updater = AppUpdater(application)
    private val repository = PhotoRepository(application)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CleanState(theme = repository.theme))
    val state = mutableState.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun notify(message: String) { viewModelScope.launch { messageChannel.send(message) } }
    fun theme(mode: ThemeMode) { repository.theme = mode; mutableState.value = state.value.copy(theme = mode) }
    fun setDeleting(value: Boolean) { mutableState.value = state.value.copy(deleting = value) }

    fun refresh() = viewModelScope.launch {
        mutex.withLock {
            val access = access()
            mutableState.value = state.value.copy(access = access)
            try {
                val photos = if (access != PhotoAccess.NONE) repository.photos() else emptyList()
                val decisions = if (access != PhotoAccess.NONE) {
                    repository.reconcilePendingDeletes(photos, access == PhotoAccess.FULL)
                } else repository.decisions()
                val deck = buildDeck(state.value.deck, photos, decisions)
                mutableState.value = state.value.copy(loading = false, access = access, photos = enrichCurrent(photos, deck),
                    decisions = decisions, deck = deck, error = null)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(loading = false, photos = emptyList(), deck = emptyList(),
                    access = access(), error = "读取照片失败，请检查照片权限后重试。")
            }
        }
    }

    fun decide(choice: Choice) {
        val snapshot = state.value
        val photo = snapshot.current ?: return
        if (snapshot.busy || choice == Choice.DELETED) return
        mutableState.value = snapshot.copy(saving = true)
        viewModelScope.launch {
            mutex.withLock {
                try {
                    repository.decide(photo, choice)
                    val decisions = repository.decisions()
                    val deck = buildDeck(state.value.deck.drop(1), state.value.photos, decisions)
                    mutableState.value = state.value.copy(saving = false, decisions = decisions,
                        photos = enrichCurrent(state.value.photos, deck), deck = deck)
                } catch (error: Exception) {
                    mutableState.value = state.value.copy(saving = false)
                    notify("未能保存这次选择，请重试。")
                }
            }
        }
    }

    fun undo() {
        val decision = state.value.undo ?: return
        restore(decision.photo.key)
    }

    fun restore(key: String) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(saving = true)
        viewModelScope.launch {
            mutex.withLock {
                try {
                    repository.restore(key)
                    val decisions = repository.decisions()
                    val deck = buildDeck(listOf(key) + state.value.deck, state.value.photos, decisions)
                    mutableState.value = state.value.copy(saving = false, decisions = decisions,
                        photos = enrichCurrent(state.value.photos, deck), deck = deck)
                } catch (error: Exception) {
                    mutableState.value = state.value.copy(saving = false)
                    notify("撤销失败，请重试。")
                }
            }
        }
    }

    suspend fun markDeleted(keys: List<String>) = mutex.withLock {
        repository.deleted(keys)
        val decisions = repository.decisions()
        mutableState.value = state.value.copy(decisions = decisions,
            photos = state.value.photos.filterNot { it.key in keys }, deck = state.value.deck.filterNot { it in keys })
    }

    /** Recheck immediately before each system request, including after returning from consent. */
    suspend fun prepareDeletion(keys: List<String>): DeletionPlan = mutex.withLock {
        val pending = repository.decisions().filter { it.choice == Choice.DELETE }.associateBy { it.photo.key }
        val plan = repository.planDeletion(keys.mapNotNull { pending[it]?.photo }, access() == PhotoAccess.FULL)
        if (plan.removed.isNotEmpty()) repository.deleted(plan.removed)
        val removed = plan.removed.toHashSet()
        mutableState.value = state.value.copy(decisions = repository.decisions(),
            photos = state.value.photos.filterNot { it.key in removed }, deck = state.value.deck.filterNot { it in removed })
        plan
    }

    fun resetKept() {
        if (state.value.busy) return
        mutableState.value = state.value.copy(saving = true)
        viewModelScope.launch {
            mutex.withLock {
                try {
                    repository.resetKept()
                    val decisions = repository.decisions()
                    val deck = buildDeck(emptyList(), state.value.photos, decisions)
                    mutableState.value = state.value.copy(saving = false, decisions = decisions,
                        photos = enrichCurrent(state.value.photos, deck), deck = deck)
                } catch (error: Exception) { mutableState.value = state.value.copy(saving = false); notify("重置失败，请重试。") }
            }
        }
    }

    private suspend fun enrichCurrent(photos: List<Photo>, deck: List<String>): List<Photo> {
        val next = deck.firstOrNull()?.let { key -> photos.find { it.key == key } } ?: return photos
        val details = repository.details(next)
        return if (details == next) photos else photos.map { if (it.key == next.key) details else it }
    }

    fun access(): PhotoAccess {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
            Build.VERSION.SDK_INT <= 32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
            else -> PhotoAccess.NONE
        }
    }
    override fun onCleared() { updater.close(); super.onCleared() }
}
