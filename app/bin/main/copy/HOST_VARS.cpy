       * HOST VARIABLES DEFINITIONS FOR SQL
         EXEC SQL BEGIN DECLARE SECTION END-EXEC.
        *SQL実行状態の戻り値
         01 SQLSTATE     PIC X(5).
         01 SQLMSG       PIC X(255).
        *郵便番号関連の集団項目（親）
         01 HOST-VARS.
            05 ZIPCODE-GROUP.
               10 ZIPCODE      PIC S9(7).
               10 ZIPCODE-PREF PIC X(2).
               10 ZIPCODE-CITY PIC X(20).
            05 ADDRESS-GROUP.
               10 ADDR-PREF    PIC N(4).
               10 ADDR-CITY    PIC X(20).
               10 ADDR-TOWN    PIC X(30).
            05 POSTAL-CODE    PIC X(8).
         EXEC SQL END DECLARE SECTION END-EXEC.
