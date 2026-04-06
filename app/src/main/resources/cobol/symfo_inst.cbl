       *>symfo_inst
       * https://symfoware.blog.fc2.com/blog-entry-31.html
         IDENTIFICATION DIVISION.
         PROGRAM-ID.   symfo_inst.
         ENVIRONMENT    DIVISION.
         CONFIGURATION  SECTION.
        *標準的な入出力受付の宣言
           SPECIAL-NAMES.
             CONSOLE IS CONS.
         INPUT-OUTPUT   SECTION.
         FILE-CONTROL.
        *読み込みファイルの指定
        *perlで編集した郵便番号-住所ファイルを読み込む
             SELECT  F1  ASSIGN  TO  "out.csv"  STATUS  FST.
         DATA DIVISION.
         FILE SECTION.
        *ファイルのレコード構成
         FD  F1.
           01 F1R.
             02  F1PAD1                    PIC X(1).
             02  F1ZIPCODE                 PIC X(7).
             02  F1PAD2                    PIC X(3).
             02  F1ADDRESS                 PIC N(50).
             02  F1PAD3                    PIC X(3).
         WORKING-STORAGE SECTION.
        *ファイルのステータス変数
          01  FST                    PIC X(02).
        *COPYを使用してホスト変数を読み込む
             COPY "HOST_VARS.cpy".
        *
         01  COUNT1       PIC 9(1)  BINARY.
         01  WORK         PIC X(12).
        *
         PROCEDURE DIVISION.
         MAIN SECTION.
        *COPYを使用してテーブル構成を読み込む
             COPY "POST_CD.cpy".
        
        *    SAMPLEデータベース接続
             EXEC SQL CONNECT TO 'SAMPLE' END-EXEC.
        
        *ファイルをオープンしてデータを取得する
             OPEN  INPUT  F1
             PERFORM  UNTIL  FST  NOT  =  "00"
                 READ  F1
                     END
                         CONTINUE
                     NOT END
        *INSERT用の変数に値を移動
                         MOVE F1ZIPCODE TO ZIPCODE
                         MOVE F1ADDRESS TO ADDR-CITY
        *INSERT実行
                         PERFORM INSERT-DATA
                 END-READ
             END-PERFORM.
             CLOSE  F1.
        
        *COMMITを実行して、データを確定させる
             EXEC SQL COMMIT WORK END-EXEC.
        *SAMPLEデータベースとの接続を切る
             EXEC SQL DISCONNECT 'SAMPLE' END-EXEC.
             MOVE  0  TO  PROGRAM-STATUS.
             EXIT PROGRAM.
        
         INSERT-DATA SECTION.
        *INSERTステートメント実行
        *:ZIPCODE,ADDR-CITYをSQL内で実行後、変数に値が格納される
             EXEC SQL
               INSERT INTO POST_CD (郵便番号,住所) VALUES (:ZIPCODE,:ADDR-CITY)
             END-EXEC.
         INSERT-DATA-END.
             EXIT.
        
         END PROGRAM symfo_inst.
