package com.hospio.common.integrations.settings;

/** How a field is typed in, checked and shown: a SECRET is never shown back, only masked ("••••••3456"). */
public enum FieldType {
    TEXT, SECRET, URL, NUMBER, BOOLEAN
}
