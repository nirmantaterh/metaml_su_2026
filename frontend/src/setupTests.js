// Custom jest matchers for DOM node assertions (testing-library/jest-dom).
import '@testing-library/jest-dom';

// Polyfill TextEncoder/TextDecoder for JSDOM environment.
import { TextEncoder, TextDecoder } from 'util';

if (typeof global.TextEncoder === 'undefined') {
    global.TextEncoder = TextEncoder;
}
if (typeof global.TextDecoder === 'undefined') {
    global.TextDecoder = TextDecoder;
}
