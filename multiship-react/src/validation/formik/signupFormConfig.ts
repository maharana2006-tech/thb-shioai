import type { SignupFormValues } from '../yup/signupSchema';

export const signupInitialValues: SignupFormValues = {
  fullName: '',
  email: '',
  username: '',
  password: '',
  confirmPassword: '',
};

export const signupFormConfig = {
  initialValues: signupInitialValues,
  enableReinitialize: true,
};
