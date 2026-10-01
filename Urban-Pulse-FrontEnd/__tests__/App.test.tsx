/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock('@react-native-firebase/app', () => ({}));
jest.mock('@react-native-firebase/auth', () => ({
  getAuth: jest.fn(() => ({
    currentUser: null,
    signOut: jest.fn().mockResolvedValue(undefined),
  })),
  signInWithPhoneNumber: jest.fn(),
  signOut: jest.fn().mockResolvedValue(undefined),
}));

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
