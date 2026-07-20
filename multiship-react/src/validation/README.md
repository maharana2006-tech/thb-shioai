# Centralized Validation System

This directory contains centralized Yup and Formik validation configurations for the application.

## Directory Structure

```
validation/
├── yup/
│   ├── loginSchema.ts       # Yup validation schema for login form
│   └── signupSchema.ts      # Yup validation schema for signup form
├── formik/
│   ├── loginFormConfig.ts   # Formik initial values and config for login
│   └── signupFormConfig.ts  # Formik initial values and config for signup
└── README.md               # This file
```

## How It Works

### Yup Schemas (`yup/`)
Contains validation schemas that define all validation rules for each form:
- **loginSchema.ts**: Validates username and password fields
- **signupSchema.ts**: Validates fullName, email, username, password, and confirmPassword fields

### Formik Configuration (`formik/`)
Contains initial form values and configuration:
- **loginFormConfig.ts**: Initial values for login form
- **signupFormConfig.ts**: Initial values for signup form

## Usage in Components

### Example: Login Component
```tsx
import { useFormik } from 'formik';
import { loginValidationSchema } from '../validation/yup/loginSchema';
import { loginFormConfig } from '../validation/formik/loginFormConfig';

export default function Login() {
  const formik = useFormik({
    initialValues: loginFormConfig.initialValues,
    validationSchema: loginValidationSchema,
    validateOnChange: true,
    validateOnBlur: true,
    onSubmit: async (values) => {
      // Handle form submission
    },
  });

  return (
    <form onSubmit={formik.handleSubmit}>
      <input {...formik.getFieldProps('username')} />
      {formik.touched.username && formik.errors.username && (
        <p>{formik.errors.username}</p>
      )}
    </form>
  );
}
```

## Validation Rules

### Login Form
- **Username**: Required, 3-20 characters
- **Password**: Required, 6-50 characters

### Signup Form
- **Full Name**: Required, 2-50 characters, letters and spaces only
- **Email**: Required, valid email format, max 100 characters
- **Username**: Required, 3-20 characters, alphanumeric with underscores and hyphens
- **Password**: Required, 8-50 characters, must contain uppercase, lowercase, and number
- **Confirm Password**: Required, must match password field

## Features

✅ **Centralized Validation**: Single source of truth for all validation rules
✅ **Type-Safe**: Uses TypeScript with Yup InferType for form values
✅ **Reusable**: Schemas and configs can be shared across multiple components
✅ **Field-Level Errors**: Shows validation errors per field with visual feedback
✅ **Real-Time Validation**: Validates on change and blur events
✅ **Form State Management**: Formik handles all form state automatically

## Adding New Forms

To add validation for a new form:

1. **Create a Yup schema** in `yup/` folder (e.g., `myFormSchema.ts`)
2. **Create a Formik config** in `formik/` folder (e.g., `myFormConfig.ts`)
3. **Import in your component** and use with `useFormik` hook
4. **Add error display** for each field using `formik.touched` and `formik.errors`

## Type Safety

All schemas export TypeScript types for form values:

```tsx
import { LoginFormValues } from '../validation/yup/loginSchema';
import { SignupFormValues } from '../validation/yup/signupSchema';

// Use types in your components and API calls
const handleSubmit = (values: SignupFormValues) => {
  // values is type-safe with all required fields
};
```
