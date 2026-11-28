# Written by Appine beside /etc/profile.d/appine.sh, which reads this file, and replaced together
# with it.
#
# bash's shell integration marks (OSC 133, see appine.sh). A login bash reads this file through the
# profile, and interactive bash also reads it through /etc/bash/appine.sh (Alpine) or
# /etc/bash.bashrc (Debian), so a nested bash (bash, su without -l) is marked too, with its own
# prompt. Reading it again changes nothing.
if [ -n "${BASH_VERSION-}" ]; then
    # A tab exiting normally must also append, so it keeps history saved by other tabs.
    if [[ $- == *i* ]]; then builtin shopt -s histappend; fi
    # PS0 runs once per submitted command, before its redirections or pipelines. History can
    # exclude commands (or be disabled); never use an older entry as the current title.
    __appine_preexec() {
        local command=bash
        if [[ -o history && $HISTCMD -gt ${__appine_history_id:-$HISTCMD} ]]; then
            command=$(HISTTIMEFORMAT= builtin history 1)
            command=${command#*[0-9][* ] }
        fi
        printf '\033]2;%s\007' "${command//[[:cntrl:]]/?}"
    }
    # Before each prompt: the end of the last command with its status (D), then the prompt's start
    # on a fresh line (A; readline redraws only the prompt's last line, and moves the cursor with
    # the arrows); the marks around PS1 (P with k=i, k=c on later lines of a multi-line PS1, B:
    # the input starts) and PS2, and the start of the output (C, from PS0), are added again
    # whenever something replaced them, so a PS1 that ~/.bashrc or anything later sets is marked
    # from the next prompt on
    __appine_prompt() {
        local status=$?
        # A force-stop skips bash's exit-time save. Flush accepted history before showing PS1.
        if [[ -o history && -n ${HISTFILE-} && ${HISTFILESIZE-} != 0 ]]; then
            builtin history -a || :
        fi
        printf '\033]133;D;%s\007\033]133;A;redraw=last;cl=line\007' "$status"
        # Restore the directory after a program's title, without letting path controls end OSC.
        printf '\033]2;%s\007' "${PWD//[[:cntrl:]]/?}"
        case $PS1 in
        *'133;B'*) ;;
        *)
            local cont='\[\e]133;P;k=c\a\]'
            local ps1=$PS1
            if [[ $ps1 == *'\n'* ]]; then
                ps1=${ps1//\\n/\\n"$cont"}
            fi
            # Only mark literal newlines when PS1 has no command substitution that could span lines.
            if [[ $ps1 == *$'\n'* && $ps1 != *'$('* && $ps1 != *'`'* ]]; then
                ps1=${ps1//$'\n'/$'\n'"$cont"}
            fi
            PS1='\[\e]133;P;k=i\a\]'$ps1'\[\e]133;B\a\]'
            ;;
        esac
        case ${PS2-} in *'133;B'*) ;; *) PS2='\[\e]133;P;k=s\a\]'${PS2-}'\[\e]133;B\a\]' ;; esac
        case ${PS0-} in *'133;C'*) ;; *) PS0=${PS0-}'\e]133;C\a' ;; esac
        case ${PS0-} in *'__appine_preexec'*) ;; *) PS0=${PS0-}'$(__appine_preexec)' ;; esac
        __appine_history_id=$HISTCMD
        return "$status"
    }
    case ${PROMPT_COMMAND[*]-} in
    *__appine_prompt*) ;;
    *)
        if [[ $(builtin declare -p PROMPT_COMMAND 2>/dev/null) == 'declare -a'* ]]; then
            PROMPT_COMMAND=(__appine_prompt "${PROMPT_COMMAND[@]}")
        else
            PROMPT_COMMAND="__appine_prompt${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
        fi
        ;;
    esac
fi
