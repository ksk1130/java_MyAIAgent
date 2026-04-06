# COBOL Column Impact Analysis Report

## Analysis Parameters

- **Table Name**: `POST_CD`
- **Column Name**: `ZIPCODE`
- **Timestamp**: 2026-04-06 22:45:35

## Identified Variables

| Variable Name | Level | Data Type | Description |
|---|---|---|---|
| `F1ZIPCODE` | 02 | PIC X(7). |  |
| `ZIPCODE-GROUP.` | 05 | N/A |  |
| `ZIPCODE` | 10 | PIC S9(7). |  |
| `ZIPCODE-PREF` | 10 | PIC X(2). |  |
| `ZIPCODE-CITY` | 10 | PIC X(20). |  |

## File-wise Variable References

### symfo_inst.cbl

**Path**: `C:\Users\kskan\Desktop\java_MyAIAgent\app\src\main\resources\cobol\symfo_inst.cbl`

| Variable Name | Level | Data Type | Description |
|---|---|---|---|
| `F1ZIPCODE` | 02 | PIC X(7). |  |

### HOST_VARS.cpy

**Path**: `C:\Users\kskan\Desktop\java_MyAIAgent\app\src\main\resources\copy\HOST_VARS.cpy`

| Variable Name | Level | Data Type | Description |
|---|---|---|---|
| `ZIPCODE-GROUP.` | 05 | N/A |  |
| `ZIPCODE` | 10 | PIC S9(7). |  |
| `ZIPCODE-PREF` | 10 | PIC X(2). |  |
| `ZIPCODE-CITY` | 10 | PIC X(20). |  |

