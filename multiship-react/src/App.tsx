import AppRoutes from './routes/AppRoutes'
import NotifyHost from './components/workspace/NotifyHost'

export default function App() {
  return (
    <>
      {/* Themed modal host — replaces the react-hot-toast Toaster.
          Renders one confirmation / info / success / error modal at a time. */}
      <NotifyHost />
      <AppRoutes />
    </>
  )
}
