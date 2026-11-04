package com.chint.audiobook.di

import android.content.Context
import com.chint.audiobook.data.repository.LibraryRepository
import com.chint.audiobook.data.repository.SettingsRepository
import com.chint.audiobook.domain.repository.BookRepository
import com.chint.audiobook.domain.repository.UserSettingsRepository
import com.chint.audiobook.domain.usecase.DeleteBookUseCase
import com.chint.audiobook.domain.usecase.GetLibraryBooksUseCase
import com.chint.audiobook.domain.usecase.ReloadLibraryUseCase
import com.chint.audiobook.domain.usecase.ObserveSettingsUseCase
import com.chint.audiobook.domain.usecase.UpdateSettingsUseCase
import com.chint.audiobook.data.repository.LedgerRepository
import com.chint.audiobook.engine.ModelManager
import com.chint.audiobook.engine.KokoroModelManager
import com.chint.audiobook.engine.KokoroTtsEngineManager
import com.chint.audiobook.engine.TtsEngineManager
import com.chint.audiobook.engine.RtfBenchmark
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
  @Binds
  @Singleton
  abstract fun bindBookRepository(impl: LibraryRepository): BookRepository

  @Binds
  @Singleton
  abstract fun bindUserSettingsRepository(impl: SettingsRepository): UserSettingsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object ApplicationFilesModule {
  @Provides
  @Singleton
  fun provideBooksDirectory(@ApplicationContext context: Context) =
    context.filesDir.resolve("books")

  @Provides
  fun provideGetLibraryBooks(repository: BookRepository) = GetLibraryBooksUseCase(repository)

  @Provides
  fun provideReloadLibrary(repository: BookRepository) = ReloadLibraryUseCase(repository)

  @Provides
  fun provideDeleteBook(repository: BookRepository) = DeleteBookUseCase(repository)

  @Provides
  fun provideObserveSettings(repository: UserSettingsRepository) = ObserveSettingsUseCase(repository)

  @Provides
  fun provideUpdateSettings(repository: UserSettingsRepository) = UpdateSettingsUseCase(repository)

  @Provides
  @Singleton
  fun provideLedgerRepository() = LedgerRepository()

  @Provides
  @Singleton
  fun provideModelManager(@ApplicationContext context: Context) = ModelManager(context)

  @Provides
  @Singleton
  fun provideKokoroModelManager(@ApplicationContext context: Context) = KokoroModelManager(context)

  @Provides
  @Singleton
  fun provideKokoroTtsEngineManager() = KokoroTtsEngineManager()

  @Provides
  @Singleton
  fun provideTtsEngineManager(@ApplicationContext context: Context) = TtsEngineManager(context)

  @Provides
  @Singleton
  fun provideRtfBenchmark(
    @ApplicationContext context: Context,
    engineManager: TtsEngineManager
  ) = RtfBenchmark(context, engineManager)
}
