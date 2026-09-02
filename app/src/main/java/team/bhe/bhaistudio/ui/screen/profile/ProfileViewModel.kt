package team.bhe.bhaistudio.ui.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.data.repository.UserProfileRepository

class ProfileViewModel(
    private val repository: UserProfileRepository
) : ViewModel() {

    /** 昵称，空串未设置 */
    val nickname: StateFlow<String> = repository.nickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 自定义头像 uri，空串未设置 */
    val avatarUri: StateFlow<String> = repository.avatarUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setNickname(value: String) = viewModelScope.launch {
        repository.setNickname(value.trim())
    }

    fun setAvatarUri(value: String) = viewModelScope.launch {
        repository.setAvatarUri(value)
    }
}
