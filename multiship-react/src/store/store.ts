import { configureStore } from '@reduxjs/toolkit'
import carrierReducer from './carrierSlice'
import orderReducer from './orderSlice'

export const store = configureStore({
  reducer: {
    carriers: carrierReducer,
    orders: orderReducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware({
      serializableCheck: false,
    }),
  devTools: import.meta.env.DEV,
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
