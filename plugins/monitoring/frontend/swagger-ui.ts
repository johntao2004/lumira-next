declare module "swagger-ui" {
  type SwaggerUISystem = {
    getComponent: (name: string, container?: string) => import("react").ComponentType<Record<string, unknown>>;
    specActions: { updateSpec: (spec: string) => void };
  };

  type SwaggerUIPlugin = () => { components: Record<string, () => null> };

  type SwaggerUIOptions = {
    spec: Record<string, unknown>;
    validatorUrl?: string | null;
    oauth2RedirectUrl?: string | null;
    supportedSubmitMethods?: string[];
    tryItOutEnabled?: boolean;
    persistAuthorization?: boolean;
    queryConfigEnabled?: boolean;
    withCredentials?: boolean;
    requestSnippetsEnabled?: boolean;
    requestInterceptor?: (request: unknown) => unknown;
    docExpansion?: "list" | "full" | "none";
    defaultModelsExpandDepth?: number;
    filter?: boolean | string;
    plugins?: SwaggerUIPlugin[];
  };

  function SwaggerUI(options: SwaggerUIOptions): SwaggerUISystem;
  export default SwaggerUI;
}

declare module "swagger-ui/dist/swagger-ui.css";
