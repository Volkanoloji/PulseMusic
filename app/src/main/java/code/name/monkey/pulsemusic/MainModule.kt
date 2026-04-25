package code.name.monkey.pulsemusic

import androidx.room.Room
import code.name.monkey.pulsemusic.auto.AutoMusicProvider
import code.name.monkey.pulsemusic.cast.PulseWebServer
import code.name.monkey.pulsemusic.db.MIGRATION_23_24
import code.name.monkey.pulsemusic.db.PulseDatabase
import code.name.monkey.pulsemusic.fragments.LibraryViewModel
import code.name.monkey.pulsemusic.fragments.albums.AlbumDetailsViewModel
import code.name.monkey.pulsemusic.fragments.artists.ArtistDetailsViewModel
import code.name.monkey.pulsemusic.fragments.genres.GenreDetailsViewModel
import code.name.monkey.pulsemusic.fragments.playlists.PlaylistDetailsViewModel
import code.name.monkey.pulsemusic.model.Genre
import code.name.monkey.pulsemusic.network.provideDefaultCache
import code.name.monkey.pulsemusic.network.provideLastFmRest
import code.name.monkey.pulsemusic.network.provideLastFmRetrofit
import code.name.monkey.pulsemusic.network.provideOkHttp
import code.name.monkey.pulsemusic.repository.*
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val networkModule = module {

    factory {
        provideDefaultCache()
    }
    factory {
        provideOkHttp(get(), get())
    }
    single {
        provideLastFmRetrofit(get())
    }
    single {
        provideLastFmRest(get())
    }
}

private val roomModule = module {

    single {
        Room.databaseBuilder(androidContext(), PulseDatabase::class.java, "playlist.db")
            .addMigrations(MIGRATION_23_24)
            .build()
    }

    factory {
        get<PulseDatabase>().playlistDao()
    }

    factory {
        get<PulseDatabase>().playCountDao()
    }

    factory {
        get<PulseDatabase>().historyDao()
    }

    single {
        RealRoomRepository(get(), get(), get())
    } bind RoomRepository::class
}
private val autoModule = module {
    single {
        AutoMusicProvider(
            androidContext(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
}
private val mainModule = module {
    single {
        androidContext().contentResolver
    }
    single {
        PulseWebServer(get())
    }
}
private val dataModule = module {
    single {
        RealRepository(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    } bind Repository::class

    single {
        RealSongRepository(get())
    } bind SongRepository::class

    single {
        RealGenreRepository(get(), get())
    } bind GenreRepository::class

    single {
        RealAlbumRepository(get())
    } bind AlbumRepository::class

    single {
        RealArtistRepository(get(), get())
    } bind ArtistRepository::class

    single {
        RealPlaylistRepository(get())
    } bind PlaylistRepository::class

    single {
        RealTopPlayedRepository(get(), get(), get(), get())
    } bind TopPlayedRepository::class

    single {
        RealLastAddedRepository(
            get(),
            get(),
            get()
        )
    } bind LastAddedRepository::class

    single {
        RealSearchRepository(
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    single {
        RealLocalDataRepository(get())
    } bind LocalDataRepository::class
}

private val viewModules = module {

    viewModel {
        LibraryViewModel(get())
    }

    viewModel { (albumId: Long) ->
        AlbumDetailsViewModel(
            get(),
            albumId
        )
    }

    viewModel { (artistId: Long?, artistName: String?) ->
        ArtistDetailsViewModel(
            get(),
            artistId,
            artistName
        )
    }

    viewModel { (playlistId: Long) ->
        PlaylistDetailsViewModel(
            get(),
            playlistId
        )
    }

    viewModel { (genre: Genre) ->
        GenreDetailsViewModel(
            get(),
            genre
        )
    }
}

val appModules = listOf(mainModule, dataModule, autoModule, viewModules, networkModule, roomModule)