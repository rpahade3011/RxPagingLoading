package ru.mobileup.rxplce

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Consumer
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import ru.mobileup.rxplce.Loading.Action
import ru.mobileup.rxplce.Loading.State

class LoadingAssembled<T>(
    refresh: Completable,
    updates: Observable<T>
) : Loading<T> {

    private val stateSubject = BehaviorSubject.createDefault<State<T>>(State()).toSerialized()
    private val actionSubject = PublishSubject.create<Action>().toSerialized()

    override val state: Observable<State<T>>
    override val actions: Consumer<Action> = Consumer { actionSubject.onNext(it) }

    init {

        state = actionSubject
            .withLatestFrom(
                stateSubject,
                BiFunction { _: Action, state: State<T> -> state }
            )
            .filter { !it.loading }
            .switchMap {
                refresh
                    .toSingleDefault(Unit)
                    .toObservable()
                    .map<InternalAction> { InternalAction.RefreshSuccess }
                    .startWith(InternalAction.RefreshStart)
                    .onErrorReturn { InternalAction.RefreshFail(it) }
            }
            .mergeWith(
                updates.map { InternalAction.UpdateData(it) }
            )
            .scan(State<T>()) { state, action ->
                when (action) {
                    is InternalAction.RefreshStart -> {
                        state.copy(
                            loading = true,
                            error = null
                        )
                    }
                    is InternalAction.RefreshSuccess -> {
                        state.copy(
                            loading = false
                        )
                    }
                    is InternalAction.RefreshFail -> {
                        state.copy(
                            loading = false,
                            error = action.error
                        )
                    }
                    is InternalAction.UpdateData<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        state.copy(
                            content = action.data as T
                        )
                    }
                }
            }
            .distinctUntilChanged()
            .doOnNext { stateSubject.onNext(it) }
            .share()
    }

    private sealed class InternalAction {
        object RefreshStart : InternalAction()
        class RefreshFail(val error: Throwable) : InternalAction()
        object RefreshSuccess : InternalAction()
        class UpdateData<T>(val data: T) : InternalAction()
    }
}