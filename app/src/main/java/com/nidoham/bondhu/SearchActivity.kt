package com.nidoham.bondhu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.SearchScreen
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint // add this import

@AndroidEntryPoint
class SearchActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Bondhu)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                SearchScreen(
                    onBack = { finish() },
                    onUserClick = { userId ->
                        NavigationHelper.navigateToProfile(this@SearchActivity, userId)
                    }
                )
            }
        }
    }
}