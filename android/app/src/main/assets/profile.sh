# Written by Appine into new systems, and replaced by Appine's later versions of it as long as it is
# left unchanged: a copy you edit is kept as it is.
#
# Shows the motd, sets the terminal and language, and gives bash, busybox ash and zsh a coloured
# prompt with shell integration: OSC 133 marks telling Appine's terminal where each prompt, the
# command typed at it and the command's output are. With them a tab waiting at its prompt closes
# without asking, Ctrl+Shift+Up and Down scroll to the previous and the next prompt, a long press
# in a command's output offers to select all of it, and a tap on the command being typed moves the
# cursor there (cl=line: the terminal sends left and right arrows). bash and zsh put the marks back
# around whatever PS1 is before each prompt: bash from /etc/profile.d/appine.bashrc, which this file
# reads (and interactive bash through /etc/bash/appine.sh on Alpine or /etc/bash.bashrc on Debian);
# ash gets them from /etc/profile.d/appine.ashrc, which ENV names so that ash reads it again after
# ~/.profile and in nested shells (including doas -s on Alpine); zsh gets them from
# /etc/profile.d/appine.zshrc, read here (and via /etc/appine/zsh/.zshenv for login zsh on Debian
# and /etc/zsh/zshrc for nested zsh).
# Before each primary prompt the title returns to the current directory. bash and zsh report the
# submitted command before execution; for ash (which has no preexec hook) and unmarked shells, the
# terminal falls back to the console's foreground command when a program sets no title. A program
# can replace the title with OSC 0/2 until the next prompt. Control characters in paths and
# commands are replaced by ?. At each primary prompt bash appends new history entries to HISTFILE
# so completed commands survive an Android force-stop.
if [ -z "${__appine_profile_seen-}" ]; then
    __appine_profile_seen=1
    cat /etc/motd 2>/dev/null
fi
export TERM=xterm-256color
export LANG=C.UTF-8
export NMAP_UNPRIVILEGED=1
# Login switches such as su - clear the console's environment; restore the browser default.
if [ -z "${BROWSER-}" ] && [ -x /usr/local/bin/xdg-open ]; then
    export BROWSER=/usr/local/bin/xdg-open
fi
# Interactive ash (and dash, which the file ignores) reads the file ENV names; one already set is
# the user's
if [ -z "${ENV-}" ] && [ -r /etc/profile.d/appine.ashrc ]; then
    export ENV=/etc/profile.d/appine.ashrc
fi
if [ -n "${BASH_VERSION-}" ]; then
    # Its marks come from appine.bashrc, which puts them around whatever PS1 is before each prompt
    PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
    if [ -r /etc/profile.d/appine.bashrc ]; then . /etc/profile.d/appine.bashrc; fi
elif [ -n "${BB_ASH_VERSION-}" ]; then
    # ash runs nothing before a command or a prompt, so its marks are in PS1 and PS2 themselves:
    # appine.ashrc puts them there, now and again when ash reads ENV
    if [ -r /etc/profile.d/appine.ashrc ]; then
        PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]$(__appine_ash_path prompt)\[\e[0m\]\$ '
        . /etc/profile.d/appine.ashrc
    fi
elif [ -n "${ZSH_VERSION-}" ]; then
    # zsh reads this file through Alpine's /etc/zsh/zprofile (in sh emulation) or Appine's
    # /etc/appine/zsh/.zshenv on Debian. Its hooks come from appine.zshrc
    PS1='%B%F{green}%n@%m%f%b:%B%F{blue}%~%f%b%(!.#.$) '
    if [ -r /etc/profile.d/appine.zshrc ]; then . /etc/profile.d/appine.zshrc; fi
fi
