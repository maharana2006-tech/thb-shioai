import { combineReducers, configureStore, createAction, type Reducer, type UnknownAction } from '@reduxjs/toolkit'
import carrierReducer from './carrierSlice'
import orderReducer from './orderSlice'

/**
 * Sprint 49 Tier 4 Fix 2 — global logout action every slice resets on.
 *
 * <p>Prior behavior: Sidebar's logout cleared localStorage + navigated
 * but left Redux state populated. Next signed-in user briefly saw the
 * previous operator's orders / carrier status / stats before the new
 * fetch overwrote them — a cross-user data leak visible in dev tools
 * and (briefly) in the UI itself.
 *
 * <p>Dispatching {@link logout} passes through the root reducer below,
 * which returns {@code undefined} for every slice. Redux Toolkit's
 * {@code combineReducers} then re-initializes each slice from its
 * {@code initialState}. New slices auto-inherit the reset — no
 * per-slice wiring required.
 */
export const logout = createAction('auth/logout')

const combinedReducer = combineReducers({
  carriers: carrierReducer,
  orders: orderReducer,
})

const rootReducer: Reducer<ReturnType<typeof combinedReducer>, UnknownAction> = (state, action) => {
  if (action.type === logout.type) {
    // Passing undefined causes combineReducers to re-init every slice.
    return combinedReducer(undefined, action)
  }
  return combinedReducer(state, action)
}

export const store = configureStore({
  reducer: rootReducer,
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware({
      serializableCheck: false,
    }),
  devTools: import.meta.env.DEV,
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
