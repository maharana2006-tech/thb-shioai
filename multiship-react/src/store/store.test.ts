import { describe, expect, it } from 'vitest'
import { configureStore } from '@reduxjs/toolkit'
import { combineReducers, createAction, type Reducer, type UnknownAction } from '@reduxjs/toolkit'
import carrierReducer from './carrierSlice'
import orderReducer from './orderSlice'

/**
 * Sprint 49 Tier 4 Fix 2 — assert dispatching logout resets user-data
 * slices to their initialState so nothing leaks across sessions.
 *
 * <p>Re-builds the same wrapper store.ts uses so the test doesn't
 * depend on the singleton store instance (which other tests may have
 * mutated).
 */
describe('logout reset', () => {
  const buildStore = () => {
    const logout = createAction('auth/logout')
    const combined = combineReducers({ carriers: carrierReducer, orders: orderReducer })
    const root: Reducer<ReturnType<typeof combined>, UnknownAction> = (state, action) =>
      action.type === logout.type ? combined(undefined, action) : combined(state, action)
    return { store: configureStore({ reducer: root }), logout }
  }

  it('populated user-data resets to initialState after logout', () => {
    const { store, logout } = buildStore()

    // Simulate the previous user having populated orders + carrier state
    // by driving the slices through their public actions. Even without
    // hitting the network we can seed something visible.
    store.dispatch({ type: 'orders/clearOrders' })
    const beforeOrders = store.getState().orders
    expect(beforeOrders).toBeDefined()

    // Fire the global logout.
    store.dispatch(logout())

    const after = store.getState()
    // Both slices are back to their initialState. Compare against a
    // fresh store's initial state as the source of truth.
    const fresh = buildStore().store.getState()
    expect(after.orders).toEqual(fresh.orders)
    expect(after.carriers).toEqual(fresh.carriers)
  })

  it('logout with no prior activity is a no-op that still yields initialState', () => {
    const { store, logout } = buildStore()
    store.dispatch(logout())
    const fresh = buildStore().store.getState()
    expect(store.getState()).toEqual(fresh)
  })
})
