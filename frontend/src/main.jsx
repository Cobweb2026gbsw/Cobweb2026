import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import App from "./App";
import RenderErrorBoundary from "./components/RenderErrorBoundary";
import "./index.css";

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <BrowserRouter>
      <RenderErrorBoundary>
        <AuthProvider>
          <App />
        </AuthProvider>
      </RenderErrorBoundary>
    </BrowserRouter>
  </StrictMode>
);
