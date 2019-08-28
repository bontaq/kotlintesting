package com.ian.weather


import arrow.Kind
import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import arrow.core.right
import arrow.effects.rx2.MaybeK
import arrow.effects.rx2.extensions.maybek.async.async
import arrow.effects.rx2.fix
import arrow.effects.typeclasses.Async
import arrow.typeclasses.ApplicativeError


//https://forecast.weather.gov/MapClick.php?lat=40.73114000000004&lon=-73.98882999999995

// https://api.weather.gov/gridpoints/TOP/31,80/forecast/hourly
//

//[{
//    "number": 156,
//    "name": "",
//    "startTime": "2019-09-03T22:00:00-05:00",
//    "endTime": "2019-09-03T23:00:00-05:00",
//    "isDaytime": false,
//    "temperature": 72,
//    "temperatureUnit": "F",
//    "temperatureTrend": null,
//    "windSpeed": "5 mph",
//    "windDirection": "S",
//    "icon": "https://api.weather.gov/icons/land/night/few?size=small",
//    "shortForecast": "Mostly Clear",
//    "detailedForecast": ""
//}]

data class Period (val temperature: Int)
data class Property (val periods: List<Period>)
data class JSON (val properties: Property)

sealed class PointLookupError : RuntimeException() // assuming you are using exceptions
data class PointNotInLocalStorage(val point: Point) : PointLookupError()
data class PointNotInRemoteStorage(val point: Point) : PointLookupError()
data class UnknownError(val underlying: Throwable) : PointLookupError()

data class Point (val x: Int, val y: Int)

data class Forecast (val temp: Int)

interface DataSource<F> {
    fun forecastByPoint(point: Point): Kind<F, List<Forecast>>
}

class LocalDataSource<F>(A: ApplicativeError<F, Throwable>) :
        DataSource<F>, ApplicativeError<F, Throwable> by A {

    private val localCache: Map<Point, List<Forecast>> =
            mapOf(Point(10, 10) to listOf(Forecast(50 )))

    override fun forecastByPoint(point: Point): Kind<F, List<Forecast>> =
            Option.fromNullable(localCache[point]).fold(
                    { raiseError(PointNotInLocalStorage(point)) },
                    { just(it) }
            )
}

class RemoteDataSource<F>(A: Async<F>): DataSource<F>, Async<F> by A {

    // TODO: use reader monad to get the request library
    private val internetSource: Map<Point, List<Forecast>> =
            mapOf(Point(10, 10) to listOf(Forecast(50 )))

    override fun forecastByPoint(point: Point): Kind<F, List<Forecast>> =
        async {
            callback: (Either<Throwable, List<Forecast>>) -> Unit ->
                Option.fromNullable(internetSource[point]).fold(
                        { callback(PointNotInRemoteStorage(point).left()) },
                        { callback(it.right())}
                )
        }

}

class ForecastRepository<F>(
    private val localDS: LocalDataSource<F>,
    private val remoteDS: RemoteDataSource<F>,
    AE: ApplicativeError<F, Throwable>): ApplicativeError<F, Throwable> by AE {

    fun forecastByPoint(point: Point): Kind<F, List<Forecast>> =
        localDS.forecastByPoint(point).handleErrorWith {
            when (it) {
                is PointNotInLocalStorage -> {
                    val p = remoteDS.forecastByPoint(point)
                    // TODO: store to cache here
                    p
                }
                else -> raiseError(UnknownError(it))
            }
        }
}

class Module<F>(A: Async<F>) {
    private val localDataSource: LocalDataSource<F> = LocalDataSource(A)
    private val remoteDataSource: RemoteDataSource<F> = RemoteDataSource(A)
    val repository: ForecastRepository<F> =
        ForecastRepository(localDataSource, remoteDataSource, A)
}

object test {

    @JvmStatic
    fun main(args: Array<String>): Unit {
        val point1 = Point(10, 10)
        val point2 = Point(20, 20)

        val singleModule = Module(MaybeK.async())
        singleModule.run {
            repository.forecastByPoint(point1).fix().maybe.subscribe({ println(it) }, { println(it) })
            repository.forecastByPoint(point2).fix().maybe.subscribe({ println(it) }, { println(it) })
        }
    }
}