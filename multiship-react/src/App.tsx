import AppRoutes from './routes/AppRoutes'
import { Toaster } from 'react-hot-toast'

export default function App() {
  return (
    <>
      <Toaster
        position="top-right"
        reverseOrder={false}
        toastOptions={{
          style: {
            fontFamily: 'Inter, Segoe UI, sans-serif',
            fontSize: '14px',
            borderRadius: '12px',
            background: '#1E3A5F',
            color: '#fff',
            boxShadow: '0 18px 48px rgba(30, 58, 95, 0.2)',
          },
          success: {
            style: {
              background: '#27AE60',
              color: '#fff',
            },
          },
          error: {
            style: {
              background: '#EB5757',
              color: '#fff',
            },
          },
          loading: {
            style: {
              background: '#2D9CDB',
              color: '#fff',
            },
          },
        }}
      />
      <AppRoutes />
    </>
  )
}
