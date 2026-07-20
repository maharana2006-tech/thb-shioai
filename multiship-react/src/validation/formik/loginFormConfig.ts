import type { LoginFormValues } from '../yup/loginSchema';

export const loginInitialValues: LoginFormValues = {
  username: '',
  password: '',
};

export const loginFormConfig = {
  initialValues: loginInitialValues,
  enableReinitialize: true,
};
