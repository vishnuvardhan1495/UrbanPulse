import { initializeApp } from "firebase/app";
import {
  getAuth,
  RecaptchaVerifier,
  signInWithPhoneNumber,
} from "firebase/auth";

const firebaseConfig = {
  apiKey: "AIzaSyCnSTjVwKPNgNcOYFinMnZS10AluOX9QXU",
  authDomain: "urbanpulse-f488c.firebaseapp.com",
  projectId: "urbanpulse-f488c",
  storageBucket: "urbanpulse-f488c.firebasestorage.app",
  messagingSenderId: "390060879164",
  appId: "1:390060879164:web:9c6daf611e1e1aec17c239",
};

const app = initializeApp(firebaseConfig);

export const auth = getAuth(app);

export { RecaptchaVerifier, signInWithPhoneNumber };