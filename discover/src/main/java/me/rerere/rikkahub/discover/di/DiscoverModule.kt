package me.rerere.rikkahub.discover.di

import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.discover.ui.ScheduleViewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val discoverModule = module {
    single { ScheduleRepository(get()) }
    viewModelOf(::ScheduleViewModel)
}
