package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.PositionsScreen
import com.example.ui.screens.RiskSettingsScreen
import com.example.ui.screens.StrategyLabScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.theme.CardBorder
import com.example.ui.theme.CardDark
import com.example.ui.theme.CardDarkElevated
import com.example.ui.theme.QuantCyan
import com.example.ui.theme.QuantAITheme
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.TradingViewModel
import com.example.ui.viewmodel.TradingViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: TradingViewModel by viewModels { TradingViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuantAITheme {
                MainApp(viewModel)
            }
        }
    }
}

@Composable
fun MainApp(viewModel: TradingViewModel) {
    val activeTab by viewModel.activeTab.collectAsState()

    // Handle Android system back button: return to Terminal if on secondary tab
    BackHandler(enabled = activeTab != 0) {
        viewModel.setActiveTab(0)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TerminalBg,
        bottomBar = {
            QuantBottomNavigationBar(
                currentTab = activeTab,
                onSelectTab = { viewModel.setActiveTab(it) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                0 -> TerminalScreen(viewModel)
                1 -> PositionsScreen(viewModel)
                2 -> StrategyLabScreen(viewModel)
                3 -> RiskSettingsScreen(viewModel)
            }
        }
    }
}

data class NavTabItem(
    val title: String,
    val icon: ImageVector,
    val testTag: String
)

@Composable
fun QuantBottomNavigationBar(
    currentTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = listOf(
        NavTabItem("Terminal", Icons.Default.TrendingUp, "nav_terminal"),
        NavTabItem("Positions", Icons.Default.AccountBalanceWallet, "nav_positions"),
        NavTabItem("Strategy Lab", Icons.Default.AutoGraph, "nav_strategy"),
        NavTabItem("Risk & Broker", Icons.Default.Security, "nav_settings")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark)
            .border(width = 1.dp, color = CardBorder)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = currentTab == index
                val itemColor = if (isSelected) QuantCyan else TextMuted

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) CardDarkElevated else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelectTab(index) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag(tab.testTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = itemColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.title,
                        color = itemColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
