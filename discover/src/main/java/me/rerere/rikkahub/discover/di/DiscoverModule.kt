package me.rerere.rikkahub.discover.di

import me.rerere.rikkahub.discover.repo.ScheduleEventRepository
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.discover.ui.SchedulePlannerViewModel
import me.rerere.rikkahub.discover.ui.ScheduleViewModel
import me.rerere.rikkahub.discover.ui.TokenReportVM
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val discoverModule = module {
    single { ScheduleRepository(get()) }
    single {
        ScheduleEventRepository(
            scheduleDAO = get(),
            eventDAO = get(),
            transactionRunner = get()
        )
    }
    viewModelOf(::ScheduleViewModel)
    viewModelOf(::SchedulePlannerViewModel)
    viewModelOf(::TokenReportVM)
}
